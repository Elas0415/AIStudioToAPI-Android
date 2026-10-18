/*
 * tinywm.c — minimal X11 window manager for the Android launcher's virtual
 * desktop.
 *
 * Why it exists: the headful Camoufox login window picks its own size, and the
 * rootfs has no window manager (and upstream passes no window-size arguments).
 * On a desktop smaller than that window the browser is drawn partly outside the
 * framebuffer, so noVNC faithfully shows a clipped browser. This WM forces
 * every normal top-level window to fill the screen, which makes the login view
 * complete at *any* 「显示分辨率」 setting — portrait included.
 *
 * Deliberately tiny, only libX11:
 *   - takes the WM role via SubstructureRedirect on the root window
 *   - MapRequest: full-screen geometry, then map and focus
 *   - ConfigureRequest: keeps managed windows full-screen
 *   - click-to-focus, so the browser still receives the keyboard
 *   - ignores override-redirect windows (menus, tooltips, combo popups) and
 *     small helper/InputOnly windows that browsers create for their own use
 *
 * If another window manager already owns the redirect it exits quietly: the
 * session keeps working exactly as it does without a WM.
 *
 * Build with scripts/build-tinywm.sh; RuntimeEnv deploys the result to
 * <rootfs>/usr/local/bin/tinywm and vnc-serve.sh starts it on the display.
 */
#include <X11/Xlib.h>
#include <stdio.h>
#include <stdlib.h>

/* Smaller than this means a helper/utility window, not the browser. */
#define MIN_TOPLEVEL 200

static Display *dpy;
static Window root;
static int screen_w, screen_h;
static int last_error;

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
    managed = realloc(managed, sizeof(Window) * (size_t)(managed_n + 1));
    if (managed == NULL) {
        return;
    }
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

/* Only real application windows: not override-redirect, not InputOnly, not one
 * of the tiny helper windows a browser creates for itself. */
static int worth_managing(Window w)
{
    XWindowAttributes a;
    if (!XGetWindowAttributes(dpy, w, &a)) {
        return 0;
    }
    if (a.override_redirect || a.class == InputOnly) {
        return 0;
    }
    if (a.width < MIN_TOPLEVEL || a.height < MIN_TOPLEVEL) {
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

int main(void)
{
    XEvent ev;

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
    {
        Window r, parent, *children = NULL;
        unsigned int n = 0, i;
        if (XQueryTree(dpy, root, &r, &parent, &children, &n)) {
            for (i = 0; i < n; i++) {
                if (worth_managing(children[i])) {
                    make_fullscreen(children[i]);
                    remember(children[i]);
                }
            }
            if (children != NULL) {
                XFree(children);
            }
        }
    }

    fprintf(stderr, "tinywm: running, screen %dx%d\n", screen_w, screen_h);

    for (;;) {
        XNextEvent(dpy, &ev);
        switch (ev.type) {
        case MapRequest:
            if (worth_managing(ev.xmaprequest.window)) {
                remember(ev.xmaprequest.window);
                make_fullscreen(ev.xmaprequest.window);
                XMapWindow(dpy, ev.xmaprequest.window);
            } else {
                XMapWindow(dpy, ev.xmaprequest.window);
            }
            break;

        case MapNotify:
            if (is_managed(ev.xmap.window)) {
                focus_window(ev.xmap.window);
            }
            break;

        case ConfigureRequest: {
            XConfigureRequestEvent *cr = &ev.xconfigurerequest;
            XWindowChanges wc;
            if (is_managed(cr->window)) {
                /* Stay full-screen: a browser refresh would otherwise shrink
                 * the window back to the size it asked for at start-up. */
                wc.x = 0;
                wc.y = 0;
                wc.width = screen_w;
                wc.height = screen_h;
                wc.border_width = 0;
                wc.sibling = None;
                wc.stack_mode = Above;
                XConfigureWindow(dpy, cr->window,
                                 CWX | CWY | CWWidth | CWHeight |
                                     CWBorderWidth | CWStackMode,
                                 &wc);
            } else {
                wc.x = cr->x;
                wc.y = cr->y;
                wc.width = cr->width;
                wc.height = cr->height;
                wc.border_width = cr->border_width;
                wc.sibling = cr->above;
                wc.stack_mode = cr->detail;
                XConfigureWindow(dpy, cr->window,
                                 (unsigned)cr->value_mask & ~CWBorderWidth,
                                 &wc);
            }
            break;
        }

        case UnmapNotify:
            forget(ev.xunmap.window);
            break;

        case DestroyNotify:
            forget(ev.xdestroywindow.window);
            break;

        case ButtonPress: {
            /* Click-to-focus. Without it X keeps the pointer-root policy and
             * the focused window would never follow the user's taps. */
            Window rroot, child;
            int rx, ry, wx, wy;
            unsigned int mask;
            if (XQueryPointer(dpy, root, &rroot, &child, &rx, &ry, &wx, &wy,
                              &mask) && child != None) {
                focus_window(child);
            }
            break;
        }

        default:
            break;
        }
    }
}
