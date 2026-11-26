package dev.llmcosttracker.auth;

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
 * Temporary way to create a project before the dashboard exists (milestone 6):
 * {@code java -jar app.jar --create-project=<name>} or {@code docker compose run --rm app --create-project=<name>}.
 * Prints the full key once to stdout and exits.
 */
@Component
public class CreateProjectCommand implements ApplicationRunner, ExitCodeGenerator {

    public static final String OPTION = "create-project";

    private final ProjectRepository projects;
    private final PrintStream out;
    private int exitCode = 0;

    @Autowired
    public CreateProjectCommand(ProjectRepository projects) {
        this(projects, System.out);
    }

    CreateProjectCommand(ProjectRepository projects, PrintStream out) {
        this.projects = projects;
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
        List<String> values = args.getOptionValues(OPTION);
        String name = values.isEmpty() ? "" : values.get(0).trim();
        if (name.isEmpty() || name.length() > 100) {
            out.println("Usage: --create-project=<name>  (1-100 characters)");
            exitCode = 2;
            return;
        }
        try {
            ProjectRepository.CreatedProject created = projects.create(name);
            out.println("Created project \"" + created.name() + "\" (id " + created.id() + ").");
            out.println("Project key, shown only once. Send it as the X-Tracker-Key header:");
            out.println(created.key());
        } catch (DuplicateKeyException e) {
            out.println("A project named \"" + name + "\" already exists.");
            exitCode = 1;
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
