/*
 * tinywm.c — minimal X11 window manager for the Android launcher's virtual
 * desktop.
 *
 * Why it exists: the headful Camoufox login window picks its own size (the
 * backend passes no window-size argument) and the rootfs has no window manager.
 * On a desktop smaller than that window the browser is drawn partly outside the
 * framebuffer, and noVNC faithfully shows a clipped browser.
 *
 * The trap this WM has to avoid: Firefox/GTK does *not* map its main window at
 * its real size. It maps a 10x10 placeholder first and only then resizes it to
 * the real geometry. Verified against the Camoufox build shipped in the rootfs
 * (xwininfo on a real X server):
 *
 *     0x200001 "Firefox": ("camoufox-default" "Camoufox-default") 10x10+10+10
 *
 * A size test that only runs at map time therefore never adopts the browser and
 * lets the real, larger-than-the-screen geometry straight through — which is
 * exactly the clipped login view (a 1280x720 or 1920x1080 window on a 1080 wide
 * desktop leaves 200..840 px outside the framebuffer, so the right-hand side of
 * the page is not in the captured image at all). Adoption therefore happens in
 * three places:
 *
 *   - MapRequest, when the window is already application-sized,
 *   - ConfigureRequest, when an unmanaged window is grown to application size
 *     (the placeholder case above),
 *   - a watchdog that re-scans the root and re-checks managed windows, so a
 *     window that appeared before this WM started, or that a client resized
 *     behind our back, still ends up on the whole screen.
 *
 * Deliberately tiny, only libX11:
 *   - takes the WM role via SubstructureRedirect on the root window
 *   - every adopted window fills the screen (0,0,WxH) and keeps it
 *   - click-to-focus, so the browser still receives the keyboard
 *   - ignores override-redirect windows (menus, tooltips, combo popups) and
 *     helper windows too small to be the browser
 *
 * If another window manager already owns the redirect it exits quietly: the
 * session keeps working exactly as it does without a WM.
 *
 * Set TINYWM_VERBOSE=1 to log every watchdog re-enforcement as well as the
 * adoptions. Build with scripts/build-tinywm.sh; RuntimeEnv deploys the result
 * to <rootfs>/usr/local/bin/tinywm and vnc-serve.sh starts it on the display.
 */
#include <X11/Xlib.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/select.h>
#include <sys/time.h>
#include <unistd.h>

/* A window at least this big in both directions is a real application window
 * (the browser). Smaller ones are placeholders and helpers; they are left at
 * the geometry they ask for, so GTK's 10x10 start-up window is not blown up. */
#define APP_MIN 100

/* Watchdog period, milliseconds. */
#define WATCH_MS 1000

static Display *dpy;
static Window root;
static int screen_w, screen_h;
static int last_error;
static int verbose;

static Window *managed;
static int managed_n;

static int handle_error(Display *display, XErrorEvent *event)
{
    (void)display;
    /* Windows can disappear between two requests; ignore those races. */
    last_error = event->error_code;
    return 0;
}

static int is_managed(Window w)
{
    int i;
    for (i = 0; i < managed_n; i++) {
        if (managed[i] == w) {
            return 1;
        }
    }
    return 0;
}

static void remember(Window w)
{
    if (is_managed(w)) {
        return;
    }
    Window *grown = realloc(managed, sizeof(Window) * (size_t)(managed_n + 1));
    if (grown == NULL) {
        return;
    }
    managed = grown;
    managed[managed_n++] = w;
}

static void forget(Window w)
{
    int i;
    for (i = 0; i < managed_n; i++) {
        if (managed[i] == w) {
            managed[i] = managed[--managed_n];
            return;
        }
    }
}

/* Only real application windows: not override-redirect, not InputOnly, and not
 * one of the tiny helper windows a browser creates for itself. */
static int worth_managing(Window w)
{
    XWindowAttributes a;
    if (!XGetWindowAttributes(dpy, w, &a)) {
        return 0;
    }
    if (a.override_redirect || a.class == InputOnly) {
        return 0;
    }
    if (a.width < APP_MIN || a.height < APP_MIN) {
        return 0;
    }
    return 1;
}

static void make_fullscreen(Window w)
{
    XMoveResizeWindow(dpy, w, 0, 0, (unsigned)screen_w, (unsigned)screen_h);
}

static void focus_window(Window w)
{
    XSetInputFocus(dpy, w, RevertToPointerRoot, CurrentTime);
}

/* Log what a window looked like just before we took it over: this is the line
 * that shows on the device whether Camoufox asked for a size wider than the
 * desktop (see README, 「登录窗口铺满桌面」). */
static void log_adoption(Window w, const char *why)
{
    XWindowAttributes a;
    if (XGetWindowAttributes(dpy, w, &a)) {
        fprintf(stderr, "tinywm: %s 0x%lx %dx%d+%d+%d -> %dx%d+0+0\n", why,
                (unsigned long)w, a.width, a.height, a.x, a.y, screen_w,
                screen_h);
    }
}

/* Adopt a window: remember it, force the full-screen geometry. Focus is left to
 * MapNotify, which is the first moment the window can take it. */
static void adopt(Window w, const char *why)
{
    if (!is_managed(w)) {
        remember(w);
    }
    log_adoption(w, why);
    make_fullscreen(w);
    XFlush(dpy);
}

/* Re-assert the full-screen geometry, but only if the window drifted. */
static void enforce(Window w)
{
    XWindowAttributes a;
    if (!XGetWindowAttributes(dpy, w, &a)) {
        return;
    }
    if (a.width == screen_w && a.height == screen_h && a.x == 0 && a.y == 0) {
        return;
    }
    if (verbose) {
        fprintf(stderr, "tinywm: watchdog 0x%lx %dx%d+%d+%d -> %dx%d+0+0\n",
                (unsigned long)w, a.width, a.height, a.x, a.y, screen_w,
                screen_h);
    }
    make_fullscreen(w);
    XFlush(dpy);
}

/* Adopt application windows that came up while we were not looking (mapped
 * before this WM started, or grown by a client whose request we passed through
 * before we knew it was the browser), then keep every adopted window full
 * screen. */
static void watchdog(void)
{
    Window r, parent, *children = NULL;
    unsigned int n = 0, i;

    if (XQueryTree(dpy, root, &r, &parent, &children, &n)) {
        for (i = 0; i < n; i++) {
            if (!is_managed(children[i]) && worth_managing(children[i])) {
                adopt(children[i], "watchdog adopt");
            }
        }
        if (children != NULL) {
            XFree(children);
        }
    }
    for (i = 0; i < (unsigned)managed_n; i++) {
        enforce(managed[i]);
    }
}

static void handle(Display *d, XEvent *ev)
{
    (void)d;
    switch (ev->type) {
    case MapRequest:
        if (worth_managing(ev->xmaprequest.window)) {
            adopt(ev->xmaprequest.window, "adopt on map");
            XMapWindow(dpy, ev->xmaprequest.window);
        } else {
            /* A placeholder (Firefox maps 10x10 first): map it at the size it
             * asked for and wait for it to grow into the browser. */
            XMapWindow(dpy, ev->xmaprequest.window);
        }
        XFlush(dpy);
        break;

    case MapNotify:
        if (is_managed(ev->xmap.window)) {
            focus_window(ev->xmap.window);
        }
        break;

    case ConfigureRequest: {
        XConfigureRequestEvent *cr = &ev->xconfigurerequest;
        XWindowChanges wc;
        if (is_managed(cr->window)) {
            /* Stay full-screen: a browser refresh, a fingerprint-driven
             * resize or a page resize would otherwise shrink the window back
             * to the size it asked for. */
            wc.x = 0;
            wc.y = 0;
            wc.width = screen_w;
            wc.height = screen_h;
            wc.border_width = 0;
            wc.sibling = None;
            wc.stack_mode = Above;
            XConfigureWindow(dpy, cr->window,
                             CWX | CWY | CWWidth | CWHeight | CWBorderWidth |
                                 CWStackMode,
                             &wc);
            XFlush(dpy);
            break;
        }
        /* Not ours yet: this is where Firefox turns its placeholder into the
         * browser, so adopt as soon as the requested size is application-like
         * instead of passing the (too large) geometry through. */
        {
            XWindowAttributes a;
            int want_w = (cr->value_mask & CWWidth) ? cr->width : -1;
            int want_h = (cr->value_mask & CWHeight) ? cr->height : -1;
            if (XGetWindowAttributes(dpy, cr->window, &a) && !a.override_redirect
                && a.class != InputOnly) {
                int w = want_w > 0 ? want_w : a.width;
                int h = want_h > 0 ? want_h : a.height;
                if (w >= APP_MIN && h >= APP_MIN) {
                    adopt(cr->window, "adopt on resize");
                    break;
                }
            }
        }
        wc.x = cr->x;
        wc.y = cr->y;
        wc.width = cr->width;
        wc.height = cr->height;
        wc.border_width = cr->border_width;
        wc.sibling = cr->above;
        wc.stack_mode = cr->detail;
        XConfigureWindow(dpy, cr->window,
                         (unsigned)cr->value_mask & ~CWBorderWidth, &wc);
        XFlush(dpy);
        break;
    }

    case UnmapNotify:
        forget(ev->xunmap.window);
        break;

    case DestroyNotify:
        forget(ev->xdestroywindow.window);
        break;

    case ButtonPress: {
        /* Click-to-focus. Without it X keeps the pointer-root policy and the
         * focused window would never follow the user's taps. */
        Window rroot, child;
        int rx, ry, wx, wy;
        unsigned int mask;
        if (XQueryPointer(dpy, root, &rroot, &child, &rx, &ry, &wx, &wy, &mask)
            && child != None) {
            focus_window(child);
        }
        break;
    }

    default:
        break;
    }
}

int main(void)
{
    XEvent ev;
    int fd;

    verbose = getenv("TINYWM_VERBOSE") != NULL;

    dpy = XOpenDisplay(NULL);
    if (dpy == NULL) {
        fprintf(stderr, "tinywm: cannot open display\n");
        return 1;
    }
    XSetErrorHandler(handle_error);
    root = DefaultRootWindow(dpy);
    screen_w = DisplayWidth(dpy, DefaultScreen(dpy));
    screen_h = DisplayHeight(dpy, DefaultScreen(dpy));

    XSelectInput(dpy, root,
                 SubstructureRedirectMask | SubstructureNotifyMask |
                     ButtonPressMask);
    XSync(dpy, False);
    if (last_error == BadAccess) {
        fprintf(stderr, "tinywm: another window manager owns the redirect\n");
        return 0;
    }

    /* Windows that already exist, in case the browser won the race. */
    watchdog();

    fprintf(stderr, "tinywm: running, screen %dx%d\n", screen_w, screen_h);
    fprintf(stderr, "tinywm: forcing every window >= %dx%d to fill the "
                    "screen\n", APP_MIN, APP_MIN);

    fd = ConnectionNumber(dpy);
    for (;;) {
        while (XPending(dpy)) {
            XNextEvent(dpy, &ev);
            handle(dpy, &ev);
        }
        XFlush(dpy);
        {
            fd_set set;
            struct timeval tv;
            FD_ZERO(&set);
            FD_SET(fd, &set);
            tv.tv_sec = WATCH_MS / 1000;
            tv.tv_usec = (WATCH_MS % 1000) * 1000;
            if (select(fd + 1, &set, NULL, NULL, &tv) <= 0) {
                watchdog();
            }
        }
    }
}
