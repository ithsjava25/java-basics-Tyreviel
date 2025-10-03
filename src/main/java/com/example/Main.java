package com.example;

import com.example.api.ElprisCLI;
import com.example.api.ElpriserAPI;
import picocli.CommandLine;

public class Main {


    public static int run(String[] args) {
        ElpriserAPI api = new ElpriserAPI();
        ElprisCLI cli = new ElprisCLI(api);
        return new CommandLine(cli).execute(args);
    }

    public static void main(String[] args) {
        int exitCode = run(args);


        if (!isRunningInTestEnvironment()) {
            System.exit(exitCode);
        }
    }

    // Identifiera testmiljö via systemproperty som vi sätter i Maven
    private static boolean isRunningInTestEnvironment() {
        return Boolean.getBoolean("test.environment");
    }
}
