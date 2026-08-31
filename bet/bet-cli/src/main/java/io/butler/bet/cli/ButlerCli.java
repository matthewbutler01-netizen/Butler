package io.butler.bet.cli;

public final class ButlerCli {

    private static final String VERSION = "0.1.0-SNAPSHOT";

    private ButlerCli() {
        // Prevent instantiation
    }

    public static void main(String[] args) {

        if (args.length == 0) {
            printHelp();
            return;
        }

        switch (args[0].toLowerCase()) {
            case "version" -> printVersion();
            case "help" -> printHelp();
            default -> {
                System.out.println("Unknown command: " + args[0]);
                System.out.println();
                printHelp();
            }
        }
    }

    private static void printVersion() {
        System.out.println("Butler Engineering Toolkit");
        System.out.println("Version: " + VERSION);
        System.out.println("Java: " + Runtime.version());
    }

    private static void printHelp() {
        System.out.println("Butler Engineering Toolkit");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  butler version");
        System.out.println("  butler help");
    }
}

