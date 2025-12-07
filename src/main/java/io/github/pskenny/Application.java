package io.github.pskenny;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.impl.Arguments;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Subparsers;
import net.sourceforge.argparse4j.inf.Namespace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Application {
    private final Logger logger = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        new Application(args);
    }

    public Application(String[] args) {
        Namespace ns = parseArguments(args);

        String command = ns.getString("command");
        switch(command) {
            case "server":
                String directory = ns.getString("directory");
                int port = ns.getInt("port");

                new Server(directory).getJavalinApp()
                        .start(port);
                break;
            case "export":
                Export.ExportConfig exportConfig = new Export.ExportConfig(
                        ns.getString("directory"),
                        ns.getString("query"),
                        ns.getString("output"),
                        ns.getString("type"),
                        ns.getString("sqlite-db"),
                        ns.getString("options"),
                        ns.getInt("depth"),
                        ns.getBoolean("dryrun"),
                        ns.getBoolean("load")
                    );

                new Export(exportConfig)
                        .export();
                break;
            default:
                logger.error("Unknown command: {}", command);
                System.exit(1);
        }
    }
    private Namespace parseArguments(String[] args) {
        ArgumentParser parser = ArgumentParsers.newFor("pkspkms").build()
                .description("PKSPKMS program");

        Subparsers subparsers = parser.addSubparsers()
                .dest("command");
        addServerSubparser(subparsers);
        addExportSubparser(subparsers);

        Namespace ns = null;
        try {
            ns = parser.parseArgs(args);
        } catch (ArgumentParserException e) {
            logger.error("Couldn't parse input: {}", e.getMessage());
            System.exit(1);
        }
        return ns;
    }

    private void addServerSubparser(Subparsers subparsers) {
        ArgumentParser serverParser = subparsers.addParser("server")
                .help("Run the pkspkms server");
        serverParser.addArgument("--port")
                .type(Integer.class)
                .setDefault(3000)
                .help("Port number for the server");
        serverParser.addArgument("--directory")
                .type(String.class)
                .required(true)
                .help("Directory to serve");
    }

    private void addExportSubparser(Subparsers subparsers) {
        ArgumentParser exportParser = subparsers.addParser("export")
                .help("Export PKSPKMS data");
        exportParser.addArgument("--directory")
                .type(String.class)
                .required(true)
                .help("Source directory");
        exportParser.addArgument("--query")
                .type(String.class)
                .setDefault("")
                .help("Query string for export");
        exportParser.addArgument("--output")
                .type(String.class)
                .required(true)
                .help("Output directory path");
        exportParser.addArgument("--type")
                .type(String.class)
                .choices("markdown", "copy")
                .required(true)
                .help("Type of export (markdown or copy)");
        exportParser.addArgument("--sqlite-db")
                .type(String.class)
                .help("Path to SQLite database");
        exportParser.addArgument("--options")
                .type(String.class)
                .help("Comma-separated options (e.g. \"copyLinkedFiles,other\" )");
        exportParser.addArgument("--depth")
                .type(Integer.class)
                .setDefault(1)
                .help("Depth for graph export.");
        exportParser.addArgument("--dryrun")
                .type(Boolean.class)
                .required(false)
                .action(Arguments.storeTrue())
                .setDefault(Boolean.FALSE)
                .help("Don't write any changes to disk.");
        exportParser.addArgument("--load")
                .action(Arguments.storeTrue())
                .setDefault(Boolean.FALSE)
                .help("Load data from previously saved (serialised) files instead of regenerating.");
    }
}
