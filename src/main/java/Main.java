import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

public class Main {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setLenient().setPrettyPrinting().create();

    private static final String DEFAULT_LOCALE = "en_us";
    private static final Function<String, Path> LOCALE_FILE_PATH = (localeCode) -> Paths.get("translations").resolve(localeCode + ".json");

    private static final Supplier<Type> MAP_TYPE = () -> new TypeToken<>() {
    }.getType();

    private static final Supplier<Map<Double, String>> TRANSLATION_FOLDER_URL_TARGETS_BY_MC_VERSION = () -> {
        Map<Double, String> versionToURL = new HashMap<>();
        versionToURL.put(1.15, "https://raw.githubusercontent.com/AOCAWOL/BYG/Forge-1.15.X/src/main/resources/assets/byg/lang");
        versionToURL.put(1.16, "https://raw.githubusercontent.com/AOCAWOL/BYG/Forge-1.16.X/src/main/resources/assets/byg/lang");
        versionToURL.put(1.18, "https://raw.githubusercontent.com/AOCAWOL/BYG/1.18.X/Common/src/main/resources/assets/byg/lang");
        return versionToURL;
    };


    public static void main(String[] args) {
        HttpClient client = HttpClient.newHttpClient();
        Map</*MC Version*/Double, Map</*Locale Code*/String, /*Translations*/Map</*Translation Key*/String, /*Translation*/String>>> byVersionAndLocale = new TreeMap<>();
        Map<Double, Set<String>> translationKeysForVersion = new HashMap<>();

        for (String localeCode : MinecraftLocaleCodes.CODES) {
            Map<String, String> combined = new TreeMap<>();
            Map<Double, String> translationFolderUrlTargetsByVersion = TRANSLATION_FOLDER_URL_TARGETS_BY_MC_VERSION.get();
            putDefaults(client, localeCode, combined, translationFolderUrlTargetsByVersion, translationKeysForVersion);
            parseAndPutTranslations(client, byVersionAndLocale, localeCode, combined, translationFolderUrlTargetsByVersion, translationKeysForVersion);
            createCombinedTranslations(localeCode, combined);
        }
        createTranslationFilesByMinecraftVersion(byVersionAndLocale);
    }

    private static void createTranslationFilesByMinecraftVersion(Map<Double, Map<String, Map<String, String>>> byVersionAndLocale) {
        byVersionAndLocale.forEach((version, byLocale) -> {
            byLocale.forEach((locale, translations) -> {
                if (translations.isEmpty()) {
                    return;
                }
                Path path = Paths.get("generated").resolve(Double.toString(version)).resolve(locale + ".json");
                try {
                    Files.createDirectories(path.getParent());
                    writeInUTF8(translations, path);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
        });
    }

    private static void createCombinedTranslations(String localeCode, Map<String, String> combined) {
        try {
            Path localeFilePath = LOCALE_FILE_PATH.apply(localeCode);
            File file = localeFilePath.toFile();
            if (file.exists()) { // Append any existing translations.
                combined.putAll(GSON.fromJson(new BufferedReader(new FileReader(file, StandardCharsets.UTF_8)), MAP_TYPE.get()));
            }

            Files.createDirectories(localeFilePath.getParent());
            writeInUTF8(combined, localeFilePath);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void parseAndPutTranslations(HttpClient client, Map<Double, Map<String, Map<String, String>>> byVersionAndLocale, String localeCode, Map<String, String> combined, Map<Double, String> translationFolderUrlTargetsByVersion, Map<Double, Set<String>> translationKeysByVersion) {
        translationFolderUrlTargetsByVersion.forEach((version, translationFileURLTarget) -> {
            Map<String, String> value = parseLocale(client, localeCode, translationFileURLTarget);
            Map<String, String> fromFileAndLocale = new TreeMap<>(value);
            try {
                // Override the translations with translations we've made.
                File file = LOCALE_FILE_PATH.apply(localeCode).toFile();
                if (file.exists()) {
                    Map<String, String> fromFile = GSON.fromJson(new BufferedReader(new FileReader(file, StandardCharsets.UTF_8)), MAP_TYPE.get());
                    translationKeysByVersion.getOrDefault(version, new HashSet<>()).forEach(validTranslationKey -> {
                        if (fromFile.containsKey(validTranslationKey)) {
                            String newTranslation = fromFile.get(validTranslationKey);
                            if (!newTranslation.startsWith("!_")) {
                                fromFileAndLocale.put(validTranslationKey, newTranslation);
                            }
                        }
                    });

                    fromFileAndLocale.forEach((translationKey, translation) -> {

                    });
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

            byVersionAndLocale.computeIfAbsent(version, version1 -> new TreeMap<>()).put(localeCode, fromFileAndLocale);

            combined.putAll(value);
        });
    }

    private static void putDefaults(HttpClient client, String localeCode, Map<String, String> combined, Map<Double, String> translationFolderUrlTargetsByVersion, Map<Double, Set<String>> keys) {
        if (!localeCode.equals(DEFAULT_LOCALE)) {
            translationFolderUrlTargetsByVersion.forEach((version, translationFileURLTarget) -> {
                Map<String, String> parseLocale = parseLocale(client, DEFAULT_LOCALE, translationFileURLTarget);
                parseLocale.forEach((translationKey, translation) -> {
                    // We mark any entries that don't exist with the game version, so users can see the object in game/know which version it's from. We start the translation with "!" so it's easy to use find(CTRL+F) to find untranslated components.
                    String versionMarkedUntranslated = String.format("!_%s_%s", version, translation);
                    combined.put(translationKey, versionMarkedUntranslated);
                });
                keys.put(version, parseLocale.keySet());
            });
        }
    }

    private static Map<String, String> parseLocale(HttpClient client, String localeCode, String translationFileURLTarget) {
        String extracted = extractedFromPage(client, String.format("%s/%s.json", translationFileURLTarget, localeCode));
        if (extracted == null) {
            extracted = "{}";
        }


        Map<String, String> value = new TreeMap<>();
        value.putAll(GSON.fromJson(extracted, MAP_TYPE.get()));
        return value;
    }

    private static void writeInUTF8(Object o, Path path) throws IOException {
        FileWriter writer = new FileWriter(path.toFile(), StandardCharsets.UTF_8);
        writer.write(GSON.toJson(o));
        writer.close();
    }

    private static String extractedFromPage(HttpClient client, String translationFileURLTarget) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(translationFileURLTarget))
            .GET()
            .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            if (body.equals("404: Not Found")) {
                return null;
            }
            return body;
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            return null;
        }
    }
}
