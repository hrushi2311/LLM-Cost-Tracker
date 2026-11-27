package dev.llmcosttracker.demo;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * {@code --seed-demo[=<project name>]}: creates a demo project with 30 days of realistic events for UI work and
 * screenshots, prints its key, and exits. Optional {@code --seed-demo-events=<n>} (default 25,000; up to
 * 5,000,000). Only ever runs when asked for on the command line.
 */
@Component
public class SeedDemoCommand implements ApplicationRunner, ExitCodeGenerator {

    public static final String OPTION = "seed-demo";
    public static final String EVENTS_OPTION = "seed-demo-events";
    static final String DEFAULT_NAME = "demo";
    static final int DEFAULT_EVENTS = 25_000;
    static final int MAX_EVENTS = 5_000_000;
    static final int DAYS = 30;

    private final DemoSeeder seeder;
    private final PrintStream out;
    private int exitCode;

    @Autowired
    public SeedDemoCommand(DemoSeeder seeder) {
        this(seeder, System.out);
    }

    /** For tests: output goes to {@code out}. */
    public SeedDemoCommand(DemoSeeder seeder, PrintStream out) {
        this.seeder = seeder;
        this.out = out;
    }

    public static boolean isRequested(String[] args) {
        return Arrays.stream(args).anyMatch(a -> a.equals("--" + OPTION) || a.startsWith("--" + OPTION + "="));
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(OPTION)) {
            return;
        }
        List<String> names = args.getOptionValues(OPTION);
        String name = names.isEmpty() || names.get(0).isBlank() ? DEFAULT_NAME : names.get(0).trim();
        if (name.length() > 100) {
            out.println("Usage: --seed-demo[=<project name>] [--seed-demo-events=<n>]  (name: 1-100 characters)");
            exitCode = 2;
            return;
        }
        int events = DEFAULT_EVENTS;
        if (args.containsOption(EVENTS_OPTION)) {
            try {
                events = Integer.parseInt(args.getOptionValues(EVENTS_OPTION).get(0).replace("_", "").trim());
            } catch (RuntimeException e) {
                events = -1;
            }
            if (events < 1 || events > MAX_EVENTS) {
                out.println("--seed-demo-events must be a whole number from 1 to " + MAX_EVENTS);
                exitCode = 2;
                return;
            }
        }
        long started = System.nanoTime();
        try {
            DemoSeeder.Result r = seeder.seed(name, events, DAYS, System.nanoTime());
            out.printf("Seeded demo project \"%s\" (id %s): %,d events in %,d runs over %d days, in %.1f s.%n",
                    r.projectName(), r.projectId(), r.events(), r.runs(), DAYS,
                    (System.nanoTime() - started) / 1e9);
            out.println("Costs use the price table where it has the model, otherwise illustrative placeholder rates.");
            out.println("Project key, shown only once. Send it as the X-Tracker-Key header:");
            out.println(r.key());
        } catch (DuplicateKeyException e) {
            out.println("A project named \"" + name + "\" already exists; pick another with --seed-demo=<name>.");
            exitCode = 1;
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
