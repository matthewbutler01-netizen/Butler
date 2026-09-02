package io.butler.bet.cli;

/**
 * Single application entry point for Butler CLI routing. Specialized command handlers remain
 * focused on their own parsing/rendering, but the application no longer relies on a chain of
 * launchers delegating to one another to discover a command.
 */
public final class ButlerCommandRouter {
    private ButlerCommandRouter() {}

    public static void main(String[] args) {
        switch (route(args)) {
            case AGE_CONTEXT -> ButlerAgeLauncher.main(args);
            case EVIDENCE -> ButlerEvidenceLauncher.main(args);
            case COMPOSED -> ButlerLauncher.main(args);
        }
    }

    static Route route(String[] args) {
        if (args != null && args.length >= 2) {
            if (equals(args[0], "league") && equals(args[1], "age-context")) {
                return Route.AGE_CONTEXT;
            }
            if (equals(args[0], "league")
                && (equals(args[1], "evidence-overview") || equals(args[1], "production-context"))) {
                return Route.EVIDENCE;
            }
            if (equals(args[0], "league")
                && (equals(args[1], "team-profile") || equals(args[1], "player-evidence-readiness"))) {
                return Route.COMPOSED;
            }
            if (equals(args[0], "nflverse")
                && (equals(args[1], "production-preview") || equals(args[1], "production-refresh"))) {
                return Route.COMPOSED;
            }
        }
        return Route.COMPOSED;
    }

    private static boolean equals(String actual, String expected) {
        return actual != null && actual.equalsIgnoreCase(expected);
    }

    enum Route { AGE_CONTEXT, EVIDENCE, COMPOSED }
}
