package com.rapid7.recog.verify;

import com.rapid7.recog.RecogMatcher;
import com.rapid7.recog.RecogMatchers;
import com.rapid7.recog.parser.ParseException;
import com.rapid7.recog.parser.RecogParser;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import static java.util.Objects.requireNonNull;

public class RecogVerifier {

  private final RecogMatchers fingerprints;
  private final VerifyReporter reporter;

  public static RecogVerifier create(VerifierOptions verifierOpts, RecogMatchers matchers) {
    return create(verifierOpts, matchers, System.out);
  }

  public static RecogVerifier create(VerifierOptions verifierOpts, RecogMatchers matchers, java.io.OutputStream output) {
    requireNonNull(verifierOpts);

    Formatter formatter = new Formatter(verifierOpts, requireNonNull(output));
    VerifyReporter reporter = new VerifyReporter(verifierOpts, formatter, matchers.getPath());
    return new RecogVerifier(requireNonNull(matchers), reporter);
  }

  public RecogVerifier(RecogMatchers fingerprints, VerifyReporter reporter) {
    this.fingerprints = fingerprints;
    this.reporter = reporter;
  }

  public RecogMatchers getFingerprints() {
    return fingerprints;
  }

  public VerifyReporter getReporter() {
    return reporter;
  }

  public void verify() {
    reporter.printPath();
    for (RecogMatcher matcher : fingerprints) {
      reporter.printName(matcher);

      // NOTE: RecogParser.parse ensures all parameters are valid
      matcher.verifyExamples((status, message) -> {
        switch (status) {
          case Warn:
            reporter.warning(String.format("WARN: %s", message));
            break;
          case Fail:
            reporter.failure(String.format("FAIL: %s", message));
            break;
          case Success:
            reporter.success(message);
            break;
          default:
            break;
        }
      });
    }
    reporter.report(fingerprints.size());
  }

  public static void main(String[] args) {
    CommandLineParser parser = new DefaultParser();
    Options options = new Options();
    options.addOption(Option.builder("f")
            .longOpt("format")
            .hasArg()
            .argName("FORMATTER")
            .type(Character.class)
            .desc("Choose a formatter.\n  [s]ummary (default - failure/warning msgs and summary\n  [q]uiet (configured failure/warning msgs only)\n  [d]etail  (fingerprint name with tests and expanded summary)")
            .build());
    options.addOption(new Option("c", "color", false, "Enable color in the output."));
    options.addOption(new Option(null, "warnings", false, "Do not track warnings"));
    options.addOption(new Option(null, "no-warnings", false, "Track warnings"));
    options.addOption(new Option("h", "help", false, "Command help"));


    VerifierOptions verifierOpts = null;
    String[] filePaths = {};
    try {
      // parse the command line arguments
      CommandLine line = parser.parse(options, args);

      if (line.hasOption("help")) {
        usage(options);
        System.exit(1);
      } else if (line.getArgs().length == 0) {
        System.err.println("Missing XML fingerprint files");
        usage(options);
        System.exit(1);
      }

      verifierOpts = getVerifierOptions(line);
      filePaths = line.getArgs();
    } catch (org.apache.commons.cli.ParseException exception) {
      System.err.println("error: command line parsing failed: " + exception.getMessage());
      System.exit(-1);
    }

    int failures = 0;
    int warnings = 0;

    for (String filePath : filePaths) {
      List<Path> globPaths = null;
      PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher("glob:" + filePath);
      try (Stream<Path> stream = Files.walk(FileSystems.getDefault().getPath(""))) {
        globPaths = stream
            .filter(Files::isRegularFile)
            .filter(pathMatcher::matches)
            .collect(Collectors.toList());
      } catch (IOException exception) {
        System.err.printf("error: processing path '%s': %s%n", filePath, exception.getMessage());
        System.exit(-1);
      }

      for (Path p : globPaths) {
        try {
          RecogParser recogParser = new RecogParser(true);
          RecogMatchers matchers = recogParser.parse(p.toFile());
          RecogVerifier verifier = RecogVerifier.create(verifierOpts, matchers);
          verifier.verify();
          failures += verifier.getReporter().getFailureCount();
          warnings += verifier.getReporter().getWarningCount();
        } catch (ParseException exception) {
          String message = exception.getCause() != null ? exception.getCause().getMessage() : exception.getMessage();
          System.err.printf("error: parsing fingerprints file '%s': %s%n", p.toFile(), message);
          System.exit(-1);
        }
      }
    }

    System.exit(failures + warnings);
  }

  private static void usage(Options options) {
    // generate the help statement
    HelpFormatter formatter = new HelpFormatter();
    formatter.printHelp("recog_verify [options] XML_FINGERPRINT_FILE1 ...",
            "Verifies that each fingerprint passes its internal tests.", options, null);
  }

  private static VerifierOptions getVerifierOptions(CommandLine line) {
    VerifierOptions verifierOpts = new VerifierOptions();

    if (line.hasOption("format")) {
      if (line.getOptionValue("format").startsWith("d")) {
        verifierOpts.setDetail(true);
      } else if (line.getOptionValue("format").startsWith("q")) {
        verifierOpts.setQuiet(true);
      }
    }

    if (line.hasOption("color")) {
      verifierOpts.setColor(true);
    }

    if (line.hasOption("warnings")) {
      verifierOpts.setWarnings(true);
    }

    if (line.hasOption("no-warnings")) {
      verifierOpts.setWarnings(false);
    }

    return verifierOpts;
  }
}
