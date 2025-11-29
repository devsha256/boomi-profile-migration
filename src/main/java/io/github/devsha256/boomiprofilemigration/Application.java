package io.github.devsha256.boomiprofilemigration;

import io.github.devsha256.boomiprofilemigration.json.JsonSampleGenerator;
import io.github.devsha256.boomiprofilemigration.json.JsonToSchemaConverter;
import io.github.devsha256.boomiprofilemigration.xml.XmlSampleGenerator;
import io.github.devsha256.boomiprofilemigration.xml.XmlToXsdConverter;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.io.File;
import java.nio.file.Files;
import java.util.Locale;

@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
public CommandLineRunner runner(XmlToXsdConverter xmlToXsdConverter,
                                XmlSampleGenerator xmlSampleGenerator,
                                JsonToSchemaConverter jsonToSchemaConverter,
                                JsonSampleGenerator jsonSampleGenerator) {
    return args -> {
        if (args == null || args.length < 2) {
            System.err.println("Usage: <operation_code> <input_file_path>");
            System.exit(2);
        }

        String op = args[0].toLowerCase(Locale.ROOT);
        File input = new File(args[1]);
        if (!input.exists() || !input.isFile()) {
            System.err.println("Input file not found: " + args[1]);
            System.exit(3);
        }

        String outputPath = generateOutputPath(input, op);
        File outputFile = new File(outputPath);

        try {
            String result = switch (op) {
                case "xml-to-xsd" -> xmlToXsdConverter.convert(input);
                case "xml-sample" -> xmlSampleGenerator.generateSample(input);
                case "json-to-schema" -> jsonToSchemaConverter.convert(input);
                case "json-sample" -> jsonSampleGenerator.generateSample(input);
                default -> {
                    System.err.println("Unknown operation: " + op);
                    System.exit(4);
                    yield ""; // unreachable
                }
            };

            Files.writeString(outputFile.toPath(), result);
            System.out.println("Generated: " + outputFile.getAbsolutePath());

        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(5);
        }

        System.exit(0);
    };
}

private String generateOutputPath(File input, String op) {
    String name = input.getName();
    int idx = name.lastIndexOf('.');
    String base = (idx > 0) ? name.substring(0, idx) : name;

    return switch (op) {
        case "xml-to-xsd" -> input.getParent() + File.separator + base + ".xsd";
        case "xml-sample" -> input.getParent() + File.separator + base + "-sample.xml";
        case "json-to-schema" -> input.getParent() + File.separator + base + ".schema.json";
        case "json-sample" -> input.getParent() + File.separator + base + "-sample.json";
        default -> throw new IllegalArgumentException("Unknown operation: " + op);
    };
}

}
