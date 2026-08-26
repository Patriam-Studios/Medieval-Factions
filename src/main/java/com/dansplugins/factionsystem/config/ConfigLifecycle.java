package com.dansplugins.factionsystem.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jooq.SQLDialect;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.ScalarEvent;
import org.yaml.snakeyaml.nodes.AnchorNode;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Strictly prepares the one operator-owned {@code config.yml} generation used by Medieval Factions.
 *
 * <p>The schema is deliberately open: template-known paths are type checked, while integration
 * extensions are retained after their nearest known siblings and accepted. Diagnostics name only
 * paths and states because both database and DPC credentials may be present anywhere in the tree.</p>
 */
public final class ConfigLifecycle {

    public static final String VERSION_KEY = "config-version";
    public static final int CURRENT_VERSION = 1;
    private static final Pattern DECIMAL_INTEGER = Pattern.compile("0|[1-9][0-9]*");
    private static final Set<String> STRING_LIST_PATHS = Set.of(
            "factions.allowedMobSpawnReasons",
            "factions.wartimePlaceableBlocks",
            "factions.wartimeBreakableBlocks",
            "factions.wartimeInteractableBlocks",
            "factions.blockedClaimWorlds"
    );

    @FunctionalInterface
    interface ConfigWriter {
        void write(Path target, String contents, byte[] expectedCurrent) throws IOException;
    }

    public enum State {
        CURRENT,
        CREATED,
        UPGRADED,
        INVALID,
        FUTURE,
        ERROR
    }

    /** One validated parsed tree and the exact physical generation which produced it. */
    public static final class Snapshot {
        private final YamlConfiguration configuration;
        private final byte[] fileBytes;

        private Snapshot(YamlConfiguration configuration, byte[] fileBytes) {
            this.configuration = Objects.requireNonNull(configuration, "configuration");
            this.fileBytes = fileBytes.clone();
        }

        public YamlConfiguration configuration() {
            return configuration;
        }

        public byte[] fileBytes() {
            return fileBytes.clone();
        }
    }

    /** Value-free result suitable for startup, command, and support diagnostics. */
    public record Result(State state, int sourceVersion, Path backup, String detail,
                         Snapshot snapshot) {
        public Result(State state, int sourceVersion, Path backup, String detail) {
            this(state, sourceVersion, backup, detail, null);
        }

        public boolean compatible() {
            return (state == State.CURRENT || state == State.CREATED || state == State.UPGRADED)
                    && snapshot != null;
        }

        public int installedVersion() {
            return compatible() ? CURRENT_VERSION : sourceVersion;
        }
    }

    private record Version(boolean valid, int value, String issue) {
        private Version(boolean valid, int value) {
            this(valid, value, null);
        }
    }

    private record Migration(YamlConfiguration configuration, String serialized) {
    }

    /** Signals an operator edit racing a prepared replacement. */
    static final class FileContentChangedException extends IOException {
        private static final long serialVersionUID = 1L;

        private FileContentChangedException() {
            super("target changed while replacement was prepared");
        }
    }

    private ConfigLifecycle() {
    }

    public static Result prepare(Path configFile, String bundledYaml) {
        return prepare(configFile, bundledYaml, ConfigLifecycle::writeUtf8AtomicRequired);
    }

    static Result prepare(Path configFile, String bundledYaml, ConfigWriter writer) {
        Objects.requireNonNull(configFile, "configFile");
        Objects.requireNonNull(bundledYaml, "bundledYaml");
        Objects.requireNonNull(writer, "writer");

        final YamlConfiguration bundled;
        Version bundledVersion = readPhysicalVersion(bundledYaml);
        if (!bundledVersion.valid() || bundledVersion.value() != CURRENT_VERSION) {
            return blocked(State.ERROR, -1,
                    "the plugin jar's config-version does not match the supported schema");
        }
        try {
            bundled = parse(bundledYaml);
            validateKnownValues(bundled, bundled);
            validateSemantics(bundled);
        } catch (InvalidConfigurationException | ValidationException | RuntimeException failure) {
            return blocked(State.ERROR, -1,
                    "the plugin jar contains an invalid default config.yml");
        }

        if (!Files.exists(configFile)) {
            try {
                writer.write(configFile, bundledYaml, null);
                byte[] expected = bundledYaml.getBytes(StandardCharsets.UTF_8);
                Snapshot snapshot = readPublishedSnapshot(configFile, expected, bundledYaml);
                return new Result(State.CREATED, CURRENT_VERSION, null,
                        "created schema v" + CURRENT_VERSION, snapshot);
            } catch (FileContentChangedException failure) {
                return blocked(State.ERROR, -1,
                        "config.yml appeared while the default was being installed; retry");
            } catch (AtomicMoveNotSupportedException failure) {
                return blocked(State.ERROR, -1,
                        "the filesystem cannot atomically install config.yml");
            } catch (IOException | InvalidConfigurationException | ValidationException
                     | RuntimeException failure) {
                return blocked(State.ERROR, -1,
                        "config.yml could not be installed and validated safely");
            }
        }

        final byte[] installedBytes;
        try {
            installedBytes = Files.readAllBytes(configFile);
        } catch (IOException failure) {
            return blocked(State.ERROR, -1, "config.yml could not be read");
        }

        final String installedText;
        try {
            installedText = decodeUtf8(installedBytes);
        } catch (CharacterCodingException failure) {
            return blocked(State.ERROR, -1, "the installed config.yml is not valid UTF-8");
        }

        Version configured = readPhysicalVersion(installedText);
        if (!configured.valid()) {
            return blocked(State.INVALID, -1, configured.issue() == null
                    ? VERSION_KEY + " must be one plain, unquoted, non-negative integer"
                    : configured.issue());
        }
        if (configured.value() > CURRENT_VERSION) {
            return blocked(State.FUTURE, configured.value(),
                    "schema v" + configured.value() + " is newer than supported schema v"
                            + CURRENT_VERSION);
        }

        final YamlConfiguration installed;
        try {
            installed = parse(installedText);
        } catch (InvalidConfigurationException | RuntimeException failure) {
            return blocked(State.ERROR, configured.value(),
                    "the installed config.yml is not valid YAML");
        }

        int sourceVersion = configured.value();
        YamlConfiguration candidate = installed;
        String migratedSerialization = null;
        int workingVersion = sourceVersion;
        while (workingVersion < CURRENT_VERSION) {
            final Migration migration;
            try {
                migration = switch (workingVersion) {
                    case 0 -> migrateZeroToOne(installedText, bundledYaml);
                    default -> null;
                };
            } catch (RuntimeException failure) {
                return blocked(State.ERROR, sourceVersion,
                        "config.yml could not be canonicalized safely");
            }
            if (migration == null) {
                return blocked(State.ERROR, sourceVersion,
                        "no migration exists from schema v" + workingVersion);
            }
            candidate = migration.configuration();
            migratedSerialization = migration.serialized();
            workingVersion++;
        }

        try {
            validateKnownValues(candidate, bundled);
            validateSemantics(candidate);
        } catch (ValidationException | RuntimeException failure) {
            String path = failure instanceof ValidationException validation
                    ? validation.path() : "a known configuration path";
            return blocked(State.INVALID, sourceVersion,
                    "invalid configuration at " + path);
        }
        candidate.setDefaults(bundled);

        if (sourceVersion == CURRENT_VERSION) {
            final byte[] verified;
            try {
                verified = Files.readAllBytes(configFile);
            } catch (IOException failure) {
                return blocked(State.ERROR, sourceVersion,
                        "config.yml could not be reread after validation");
            }
            if (!Arrays.equals(installedBytes, verified)) {
                return blocked(State.ERROR, sourceVersion,
                        "config.yml changed while it was being validated; retry");
            }
            return new Result(State.CURRENT, sourceVersion, null,
                    "schema v" + CURRENT_VERSION + " is current",
                    new Snapshot(candidate, verified));
        }

        String serialized = Objects.requireNonNull(migratedSerialization,
                "a schema migration must provide canonical serialized YAML");
        byte[] publishedBytes = serialized.getBytes(StandardCharsets.UTF_8);
        final Path backup;
        try {
            backup = createVerifiedBackup(configFile, installedBytes, sourceVersion);
        } catch (FileContentChangedException failure) {
            return blocked(State.ERROR, sourceVersion,
                    "config.yml changed while its migration backup was prepared; retry");
        } catch (IOException failure) {
            return blocked(State.ERROR, sourceVersion,
                    "config.yml could not be backed up safely");
        }

        try {
            writer.write(configFile, serialized, installedBytes);
        } catch (FileContentChangedException failure) {
            return new Result(State.ERROR, sourceVersion, backup,
                    "config.yml changed while its migration replacement was prepared; the "
                            + "operator's file was not replaced");
        } catch (AtomicMoveNotSupportedException failure) {
            return new Result(State.ERROR, sourceVersion, backup,
                    "the filesystem cannot atomically replace config.yml");
        } catch (IOException failure) {
            return new Result(State.ERROR, sourceVersion, backup,
                    "the migrated config.yml could not replace the installed file");
        }

        try {
            Snapshot published = readPublishedSnapshot(configFile, publishedBytes, bundledYaml);
            return new Result(State.UPGRADED, sourceVersion, backup,
                    "upgraded schema v" + sourceVersion + " -> v" + CURRENT_VERSION,
                    published);
        } catch (FileContentChangedException failure) {
            return new Result(State.ERROR, sourceVersion, backup,
                    "config.yml changed after migration was published; it was not activated");
        } catch (IOException | InvalidConfigurationException | ValidationException
                 | RuntimeException failure) {
            return new Result(State.ERROR, sourceVersion, backup,
                    "the published config.yml failed exact post-write validation");
        }
    }

    /**
     * Safely publishes a plugin-owned edit from one exact active generation.
     *
     * <p>No backup is made because this is not a schema migration. A concurrent operator edit
     * refuses the write, and the caller keeps the previous snapshot as its last known good.</p>
     */
    public static Result update(Path configFile, String bundledYaml, Snapshot active,
                                Map<String, ?> updates) {
        Objects.requireNonNull(configFile, "configFile");
        Objects.requireNonNull(bundledYaml, "bundledYaml");
        Objects.requireNonNull(active, "active");
        Objects.requireNonNull(updates, "updates");

        for (Map.Entry<String, ?> update : updates.entrySet()) {
            String path = update.getKey();
            if (path == null || path.isBlank() || VERSION_KEY.equals(path)
                    || path.startsWith(VERSION_KEY + ".")) {
                return blocked(State.INVALID, CURRENT_VERSION,
                        "a plugin-owned update targeted a reserved configuration path");
            }
        }
        final YamlConfiguration candidate;
        final YamlConfiguration bundled;
        final String serialized;
        try {
            String activeYaml = decodeUtf8(active.fileBytes);
            serialized = serializePluginUpdates(activeYaml, bundledYaml, updates);
            candidate = parse(serialized);
            bundled = parse(bundledYaml);
        } catch (CharacterCodingException | InvalidConfigurationException | RuntimeException failure) {
            return blocked(State.ERROR, CURRENT_VERSION,
                    "the active configuration snapshot could not be prepared for update");
        }
        try {
            validateKnownValues(candidate, bundled);
            validateSemantics(candidate);
        } catch (ValidationException | RuntimeException failure) {
            String path = failure instanceof ValidationException validation
                    ? validation.path() : "a known configuration path";
            return blocked(State.INVALID, CURRENT_VERSION,
                    "invalid configuration at " + path);
        }

        byte[] publishedBytes = serialized.getBytes(StandardCharsets.UTF_8);
        try {
            writeUtf8AtomicRequired(configFile, serialized, active.fileBytes);
            Snapshot published = readPublishedSnapshot(configFile, publishedBytes, bundledYaml);
            return new Result(State.CURRENT, CURRENT_VERSION, null,
                    "schema v" + CURRENT_VERSION + " plugin-owned update applied", published);
        } catch (FileContentChangedException failure) {
            return blocked(State.ERROR, CURRENT_VERSION,
                    "config.yml changed before the plugin-owned update; the edit was refused");
        } catch (AtomicMoveNotSupportedException failure) {
            return blocked(State.ERROR, CURRENT_VERSION,
                    "the filesystem cannot atomically replace config.yml");
        } catch (IOException | InvalidConfigurationException | ValidationException
                 | RuntimeException failure) {
            return blocked(State.ERROR, CURRENT_VERSION,
                    "the plugin-owned config.yml update could not be published safely");
        }
    }

    /** Identifies the separate historical MF4 import trigger before schema-zero adoption. */
    public static boolean isMf4Legacy(Path configFile) {
        if (!Files.isRegularFile(configFile)) {
            return false;
        }
        try {
            String text = decodeUtf8(Files.readAllBytes(configFile));
            Version version = readPhysicalVersion(text);
            if (!version.valid() || version.value() != 0) {
                return false;
            }
            YamlConfiguration legacy = parse(text);
            Object release = legacy.get("version");
            return release instanceof String string && string.startsWith("v4.");
        } catch (IOException | InvalidConfigurationException | RuntimeException failure) {
            return false;
        }
    }

    private static Result blocked(State state, int sourceVersion, String detail) {
        return new Result(state, sourceVersion, null, detail, null);
    }

    /** Schema 0 is a valid installed MF5-era file from before this marker existed. */
    private static Migration migrateZeroToOne(String installedYaml, String bundledYaml) {
        final String serialized;
        try {
            LoaderOptions loaderOptions = new LoaderOptions();
            loaderOptions.setAllowDuplicateKeys(false);
            loaderOptions.setProcessComments(true);
            DumperOptions dumperOptions = new DumperOptions();
            dumperOptions.setProcessComments(true);
            dumperOptions.setIndent(2);
            dumperOptions.setIndicatorIndent(0);
            Representer representer = new Representer(dumperOptions);
            Yaml yaml = new Yaml(new SafeConstructor(loaderOptions), representer,
                    dumperOptions, loaderOptions);
            boolean installedHasYaml = installedYaml.lines()
                    .map(String::trim)
                    .anyMatch(line -> !line.isEmpty() && !line.startsWith("#"));
            Object installedRoot = installedHasYaml ? yaml.load(installedYaml) : null;
            Object templateValuesRoot = yaml.load(bundledYaml);
            Node templateRoot = yaml.compose(new StringReader(bundledYaml));
            final Map<?, ?> installedMapping;
            if (installedRoot == null) {
                installedMapping = Collections.emptyMap();
            } else if (installedRoot instanceof Map<?, ?> mapping) {
                installedMapping = mapping;
            } else {
                throw new IllegalStateException("validated configs must have mapping roots");
            }
            if (!(templateValuesRoot instanceof Map<?, ?> templateValues)
                    || !(templateRoot instanceof MappingNode templateMapping)) {
                throw new IllegalStateException("validated configs must have mapping roots");
            }
            overlayNodeMapping(installedMapping, templateValues, templateMapping, yaml, true);
            StringWriter output = new StringWriter();
            yaml.serialize(templateMapping, output);
            serialized = output.toString();
            YamlConfiguration candidate = parse(serialized);
            return new Migration(candidate, serialized);
        } catch (InvalidConfigurationException | YAMLException impossible) {
            throw new IllegalStateException("validated YAML could not be canonicalized", impossible);
        }
    }

    /** Template comments/order/anchors remain; extensions follow their nearest known siblings. */
    private static void overlayNodeMapping(Map<?, ?> installed, Map<?, ?> templateValues,
                                           MappingNode template, Yaml yaml, boolean root) {
        List<NodeTuple> templateTuples = template.getValue();
        for (Map.Entry<?, ?> installedEntry : installed.entrySet()) {
            String installedKey = installedEntry.getKey() instanceof String string ? string : null;
            if (root && VERSION_KEY.equals(installedKey)) {
                continue;
            }
            int existingIndex = -1;
            for (int index = 0; index < templateTuples.size(); index++) {
                if (Objects.equals(installedKey,
                        scalarKey(templateTuples.get(index).getKeyNode()))) {
                    existingIndex = index;
                    break;
                }
            }
            if (existingIndex < 0) {
                templateTuples.add(new NodeTuple(
                        yaml.represent(installedEntry.getKey()),
                        yaml.represent(installedEntry.getValue())
                ));
                continue;
            }
            NodeTuple templateTuple = templateTuples.get(existingIndex);
            Object installedValue = installedEntry.getValue();
            Object templateValueObject = templateValues.get(installedEntry.getKey());
            if (Objects.deepEquals(installedValue, templateValueObject)) {
                continue;
            }
            Node templateValue = unwrap(templateTuple.getValueNode());
            if (installedValue instanceof Map<?, ?> installedChild
                    && templateValueObject instanceof Map<?, ?> templateValuesChild
                    && templateValue instanceof MappingNode templateChild) {
                overlayNodeMapping(installedChild, templateValuesChild, templateChild, yaml,
                        false);
            } else {
                Node replacement = yaml.represent(installedValue);
                copyTemplateComments(templateValue, replacement);
                templateTuples.set(existingIndex,
                        new NodeTuple(templateTuple.getKeyNode(), replacement));
            }
        }
    }

    private static String scalarKey(Node node) {
        Node unwrapped = unwrap(node);
        return unwrapped instanceof ScalarNode scalar ? scalar.getValue() : null;
    }

    private static Node unwrap(Node node) {
        return node instanceof AnchorNode anchor ? anchor.getRealNode() : node;
    }

    private static void copyTemplateComments(Node template, Node replacement) {
        if (template.getBlockComments() != null) {
            replacement.setBlockComments(template.getBlockComments());
        }
        if (template.getInLineComments() != null) {
            replacement.setInLineComments(template.getInLineComments());
        }
        if (template.getEndComments() != null) {
            replacement.setEndComments(template.getEndComments());
        }
    }

    /** Replaces only named paths on the exact active node tree, retaining its comments/aliases. */
    private static String serializePluginUpdates(String activeYaml, String bundledYaml,
                                                 Map<String, ?> updates) {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setAllowDuplicateKeys(false);
        loaderOptions.setProcessComments(true);
        DumperOptions dumperOptions = new DumperOptions();
        dumperOptions.setProcessComments(true);
        dumperOptions.setIndent(2);
        dumperOptions.setIndicatorIndent(0);
        Yaml yaml = new Yaml(new SafeConstructor(loaderOptions),
                new Representer(dumperOptions), dumperOptions, loaderOptions);
        Node activeRoot = yaml.compose(new StringReader(activeYaml));
        Node templateRoot = yaml.compose(new StringReader(bundledYaml));
        if (!(activeRoot instanceof MappingNode activeMapping)
                || !(templateRoot instanceof MappingNode templateMapping)) {
            throw new IllegalStateException("validated configs must have mapping roots");
        }
        for (Map.Entry<String, ?> update : updates.entrySet()) {
            applyNodeUpdate(activeMapping, templateMapping, update.getKey().split("\\."),
                    0, update.getValue(), yaml);
        }
        StringWriter output = new StringWriter();
        yaml.serialize(activeMapping, output);
        return output.toString();
    }

    private static void applyNodeUpdate(MappingNode active, MappingNode template,
                                        String[] segments, int depth, Object value, Yaml yaml) {
        String segment = segments[depth];
        int activeIndex = tupleIndex(active, segment);
        int templateIndex = tupleIndex(template, segment);
        NodeTuple activeTuple = activeIndex < 0 ? null : active.getValue().get(activeIndex);
        NodeTuple templateTuple = templateIndex < 0 ? null : template.getValue().get(templateIndex);

        if (depth == segments.length - 1) {
            if (value == null) {
                if (activeIndex >= 0) {
                    active.getValue().remove(activeIndex);
                }
                return;
            }
            Node replacement = yaml.represent(value);
            Node commentSource = activeTuple == null
                    ? templateTuple == null ? null : unwrap(templateTuple.getValueNode())
                    : unwrap(activeTuple.getValueNode());
            if (commentSource != null) {
                copyTemplateComments(commentSource, replacement);
            }
            Node key = activeTuple == null
                    ? representedKey(segment, templateTuple, yaml)
                    : activeTuple.getKeyNode();
            NodeTuple replacementTuple = new NodeTuple(key, replacement);
            if (activeIndex >= 0) {
                active.getValue().set(activeIndex, replacementTuple);
            } else {
                insertCanonical(active, template, templateIndex, replacementTuple);
            }
            return;
        }

        MappingNode activeChild;
        if (activeTuple == null) {
            Node represented = yaml.represent(Collections.emptyMap());
            if (!(represented instanceof MappingNode created)) {
                throw new IllegalStateException("an empty map was not represented as a mapping");
            }
            created.setFlowStyle(DumperOptions.FlowStyle.BLOCK);
            Node key = representedKey(segment, templateTuple, yaml);
            NodeTuple createdTuple = new NodeTuple(key, created);
            insertCanonical(active, template, templateIndex, createdTuple);
            activeChild = created;
        } else {
            Node unwrapped = unwrap(activeTuple.getValueNode());
            if (!(unwrapped instanceof MappingNode mapping)) {
                throw new IllegalStateException("an update traversed a scalar configuration path");
            }
            activeChild = mapping;
        }

        MappingNode templateChild;
        if (templateTuple != null
                && unwrap(templateTuple.getValueNode()) instanceof MappingNode mapping) {
            templateChild = mapping;
        } else {
            Node represented = yaml.represent(Collections.emptyMap());
            if (!(represented instanceof MappingNode empty)) {
                throw new IllegalStateException("an empty map was not represented as a mapping");
            }
            templateChild = empty;
        }
        applyNodeUpdate(activeChild, templateChild, segments, depth + 1, value, yaml);
    }

    private static int tupleIndex(MappingNode mapping, String key) {
        for (int index = 0; index < mapping.getValue().size(); index++) {
            if (key.equals(scalarKey(mapping.getValue().get(index).getKeyNode()))) {
                return index;
            }
        }
        return -1;
    }

    private static Node representedKey(String segment, NodeTuple templateTuple, Yaml yaml) {
        Node represented = yaml.represent(segment);
        if (templateTuple != null) {
            copyTemplateComments(unwrap(templateTuple.getKeyNode()), represented);
        }
        return represented;
    }

    private static void insertCanonical(MappingNode active, MappingNode template,
                                        int templateIndex, NodeTuple tuple) {
        if (templateIndex < 0) {
            active.getValue().add(tuple);
            return;
        }
        int insertion = active.getValue().size();
        for (int index = 0; index < active.getValue().size(); index++) {
            String activeKey = scalarKey(active.getValue().get(index).getKeyNode());
            int activeTemplateIndex = tupleIndex(template, activeKey);
            if (activeTemplateIndex < 0 || activeTemplateIndex > templateIndex) {
                insertion = index;
                break;
            }
        }
        active.getValue().add(insertion, tuple);
    }

    /** Validates only bundled paths; unknown integration extensions are deliberately accepted. */
    private static void validateKnownValues(ConfigurationSection configured,
                                            ConfigurationSection template)
            throws ValidationException {
        validateKnownValues(configured, template, "");
    }

    private static void validateKnownValues(ConfigurationSection configured,
                                            ConfigurationSection template,
                                            String parentPath)
            throws ValidationException {
        for (String key : template.getKeys(false)) {
            if (VERSION_KEY.equals(key) && parentPath.isEmpty()) {
                continue;
            }
            if (!configured.contains(key)) {
                continue;
            }
            String path = parentPath.isEmpty() ? key : parentPath + "." + key;
            ConfigurationSection expectedSection = template.getConfigurationSection(key);
            ConfigurationSection actualSection = configured.getConfigurationSection(key);
            if (expectedSection != null) {
                if (actualSection == null) {
                    throw new ValidationException(path);
                }
                validateKnownValues(actualSection, expectedSection, path);
                continue;
            }
            if (actualSection != null
                    || !compatibleLeaf(configured.get(key), template.get(key), path)) {
                throw new ValidationException(path);
            }
        }
    }

    private static boolean compatibleLeaf(Object actual, Object expected, String path) {
        if (actual == null || expected == null) {
            return false;
        }
        if (expected instanceof String) {
            return actual instanceof String;
        }
        if (expected instanceof Boolean) {
            return actual instanceof Boolean;
        }
        if (expected instanceof Byte || expected instanceof Short
                || expected instanceof Integer || expected instanceof Long) {
            return actual instanceof Byte || actual instanceof Short
                    || actual instanceof Integer || actual instanceof Long;
        }
        if (expected instanceof Float || expected instanceof Double) {
            return actual instanceof Number number && Double.isFinite(number.doubleValue());
        }
        if (expected instanceof List<?> expectedList) {
            if (!(actual instanceof List<?> actualList)) {
                return false;
            }
            if (STRING_LIST_PATHS.contains(path)) {
                return actualList.stream().allMatch(String.class::isInstance);
            }
            if ("gates.restrictedBlocks".equals(path)) {
                return actualList.stream().allMatch(item -> item instanceof String
                        || item instanceof List<?> group
                        && group.stream().allMatch(String.class::isInstance));
            }
            if (!expectedList.isEmpty()
                    && expectedList.stream().allMatch(String.class::isInstance)) {
                return actualList.stream().allMatch(String.class::isInstance);
            }
            return true;
        }
        return expected.getClass().isInstance(actual);
    }

    /** A small set of semantics whose callers otherwise throw or silently coerce. */
    private static void validateSemantics(ConfigurationSection configured)
            throws ValidationException {
        String duration = configured.getString("duels.duration");
        if (duration != null) {
            try {
                Duration parsed = Duration.parse(duration);
                if (parsed.isNegative() || parsed.isZero()) {
                    throw new ValidationException("duels.duration");
                }
            } catch (DateTimeParseException failure) {
                throw new ValidationException("duels.duration");
            }
        }
        if (configured.contains("dpc-api.sync-interval-minutes")
                && configured.getInt("dpc-api.sync-interval-minutes") < 1) {
            throw new ValidationException("dpc-api.sync-interval-minutes");
        }
        if (configured.contains("dynmap.fillOpacity")) {
            double opacity = configured.getDouble("dynmap.fillOpacity");
            if (!Double.isFinite(opacity) || opacity < 0.0 || opacity > 1.0) {
                throw new ValidationException("dynmap.fillOpacity");
            }
        }
        String databaseUrl = configured.getString("database.url");
        if (databaseUrl != null && databaseUrl.isBlank()) {
            throw new ValidationException("database.url");
        }
        String dialect = configured.getString("database.dialect");
        if (dialect != null) {
            try {
                SQLDialect.valueOf(dialect);
            } catch (IllegalArgumentException failure) {
                throw new ValidationException("database.dialect");
            }
        }
    }

    private static final class ValidationException extends Exception {
        private static final long serialVersionUID = 1L;
        private final String path;

        private ValidationException(String path) {
            super(path);
            this.path = path;
        }

        private String path() {
            return path;
        }
    }

    /** Node inspection preserves physical style, aliases, tags and YAML nulls Bukkit drops. */
    private static Version readPhysicalVersion(String physicalText) {
        final Node root;
        final Yaml yaml;
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            yaml = new Yaml(new SafeConstructor(options));
            yaml.load(physicalText);
            root = yaml.compose(new StringReader(physicalText));
        } catch (YAMLException failure) {
            return new Version(false, -1,
                    "config.yml contains duplicate or ambiguous YAML keys");
        }

        if (root == null) {
            boolean syntaxBeyondComments = physicalText.lines()
                    .map(String::trim)
                    .anyMatch(line -> !line.isEmpty() && !line.startsWith("#"));
            return syntaxBeyondComments
                    ? new Version(false, -1,
                    "config.yml contains a null value that cannot be preserved")
                    : new Version(true, 0);
        }
        if (!(root instanceof MappingNode mapping)) {
            return new Version(false, -1, "config.yml must contain a top-level mapping");
        }
        if (containsNull(root, Collections.newSetFromMap(new IdentityHashMap<>()))) {
            return new Version(false, -1,
                    "config.yml contains a null value that cannot be preserved");
        }
        if (containsNonStringMappingKey(root,
                Collections.newSetFromMap(new IdentityHashMap<>()))) {
            return new Version(false, -1,
                    "config.yml keys must be YAML strings");
        }

        List<NodeTuple> declarations = mapping.getValue().stream()
                .filter(tuple -> semanticVersionKey(tuple.getKeyNode()))
                .toList();
        if (declarations.isEmpty()) {
            return new Version(true, 0);
        }
        if (declarations.size() != 1) {
            return new Version(false, -1, VERSION_KEY + " must be declared exactly once");
        }

        NodeTuple declaration = declarations.get(0);
        if (!(declaration.getKeyNode() instanceof ScalarNode key)
                || key.getScalarStyle() != DumperOptions.ScalarStyle.PLAIN
                || !Tag.STR.equals(key.getTag())
                || key.getAnchor() != null
                || hasExplicitTag(yaml, physicalText, key)) {
            return new Version(false, -1,
                    VERSION_KEY + " must use its plain, unquoted key spelling");
        }
        if (!(declaration.getValueNode() instanceof ScalarNode value)
                || value.getScalarStyle() != DumperOptions.ScalarStyle.PLAIN
                || !Tag.INT.equals(value.getTag())
                || value.getAnchor() != null
                || hasExplicitTag(yaml, physicalText, value)
                || !DECIMAL_INTEGER.matcher(value.getValue()).matches()) {
            return new Version(false, -1,
                    VERSION_KEY + " must be a plain, unquoted decimal integer");
        }
        try {
            return new Version(true, Integer.parseInt(value.getValue()));
        } catch (NumberFormatException failure) {
            return new Version(false, -1,
                    VERSION_KEY + " is outside the supported integer range");
        }
    }

    private static boolean semanticVersionKey(Node node) {
        Node unwrapped = node instanceof AnchorNode anchor ? anchor.getRealNode() : node;
        return unwrapped instanceof ScalarNode scalar && VERSION_KEY.equals(scalar.getValue());
    }

    private static boolean hasExplicitTag(Yaml yaml, String text, ScalarNode target) {
        try {
            for (Event event : yaml.parse(new StringReader(text))) {
                if (event instanceof ScalarEvent scalar
                        && scalar.getStartMark().getIndex() == target.getStartMark().getIndex()) {
                    return scalar.getTag() != null;
                }
            }
        } catch (YAMLException failure) {
            return true;
        }
        return true;
    }

    private static boolean containsNull(Node node, Set<Node> visited) {
        Node unwrapped = node instanceof AnchorNode anchor ? anchor.getRealNode() : node;
        if (!visited.add(unwrapped)) {
            return false;
        }
        if (Tag.NULL.equals(unwrapped.getTag())) {
            return true;
        }
        if (unwrapped instanceof MappingNode mapping) {
            for (NodeTuple tuple : mapping.getValue()) {
                if (containsNull(tuple.getKeyNode(), visited)
                        || containsNull(tuple.getValueNode(), visited)) {
                    return true;
                }
            }
        } else if (unwrapped instanceof SequenceNode sequence) {
            for (Node child : sequence.getValue()) {
                if (containsNull(child, visited)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsNonStringMappingKey(Node node, Set<Node> visited) {
        Node unwrapped = unwrap(node);
        if (!visited.add(unwrapped)) {
            return false;
        }
        if (unwrapped instanceof MappingNode mapping) {
            for (NodeTuple tuple : mapping.getValue()) {
                Node key = unwrap(tuple.getKeyNode());
                if (!(key instanceof ScalarNode scalar) || !Tag.STR.equals(scalar.getTag())) {
                    return true;
                }
                if (containsNonStringMappingKey(tuple.getValueNode(), visited)) {
                    return true;
                }
            }
        } else if (unwrapped instanceof SequenceNode sequence) {
            for (Node child : sequence.getValue()) {
                if (containsNonStringMappingKey(child, visited)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Snapshot readPublishedSnapshot(Path file, byte[] expected, String bundledYaml)
            throws IOException, InvalidConfigurationException, ValidationException {
        byte[] actual = Files.readAllBytes(file);
        if (!Arrays.equals(expected, actual)) {
            throw new FileContentChangedException();
        }
        String text = decodeUtf8(actual);
        Version version = readPhysicalVersion(text);
        if (!version.valid() || version.value() != CURRENT_VERSION) {
            throw new InvalidConfigurationException(
                    "published config marker does not match the supported schema");
        }
        YamlConfiguration published = parse(text);
        YamlConfiguration bundled = parse(bundledYaml);
        validateKnownValues(published, bundled);
        validateSemantics(published);
        published.setDefaults(bundled);
        return new Snapshot(published, actual);
    }

    private static YamlConfiguration parse(String text) throws InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        yaml.loadFromString(text);
        return yaml;
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes));
        return decoded.toString();
    }

    private static Path createVerifiedBackup(Path configFile, byte[] expected,
                                              int sourceVersion) throws IOException {
        Path parent = configFile.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IOException("config.yml has no parent directory");
        }
        if (!Arrays.equals(expected, Files.readAllBytes(configFile))) {
            throw new FileContentChangedException();
        }
        String base = configFile.getFileName() + ".v" + sourceVersion + ".bak";
        for (int suffix = 0; ; suffix++) {
            Path backup = parent.resolve(suffix == 0 ? base : base + "." + suffix);
            boolean created = false;
            try {
                createOwnerOnlyFile(backup);
                created = true;
                try (FileChannel channel = FileChannel.open(backup, StandardOpenOption.WRITE)) {
                    ByteBuffer buffer = ByteBuffer.wrap(expected);
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                    channel.force(true);
                }
            } catch (FileAlreadyExistsException collision) {
                continue;
            } catch (IOException failure) {
                if (created) {
                    Files.deleteIfExists(backup);
                }
                throw failure;
            }
            if (!Arrays.equals(expected, Files.readAllBytes(backup))) {
                Files.deleteIfExists(backup);
                throw new IOException("backup verification failed");
            }
            return backup;
        }
    }

    static void writeUtf8AtomicRequired(Path target, String content,
                                        byte[] expectedCurrent) throws IOException {
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IOException("config.yml has no parent directory");
        }
        Files.createDirectories(parent);
        Path temporary = parent.resolve("." + target.getFileName() + "."
                + UUID.randomUUID() + ".tmp");
        boolean moved = false;
        try {
            byte[] encoded = content.getBytes(StandardCharsets.UTF_8);
            createOwnerOnlyFile(temporary);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(encoded);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            if (expectedCurrent == null) {
                if (Files.exists(target)) {
                    throw new FileContentChangedException();
                }
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } else if (!Files.exists(target)
                    || !Arrays.equals(expectedCurrent, Files.readAllBytes(target))) {
                throw new FileContentChangedException();
            } else {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    /** Restricts an already-existing credential-bearing file to its owner. */
    public static void restrictToOwner(Path file) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(file,
                PosixFileAttributeView.class);
        if (posix != null) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(file,
                AclFileAttributeView.class);
        if (acl == null) {
            throw new IOException("filesystem exposes neither POSIX permissions nor ACLs");
        }
        AclEntry owner = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(Files.getOwner(file))
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .build();
        acl.setAcl(List.of(owner));
    }

    /** Creates an empty owner-only file before any possibly sensitive bytes are copied. */
    private static void createOwnerOnlyFile(Path file) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(file,
                PosixFileAttributeView.class);
        if (posix != null) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------")));
            return;
        }
        Files.createFile(file);
        boolean secured = false;
        try {
            AclFileAttributeView acl = Files.getFileAttributeView(file,
                    AclFileAttributeView.class);
            if (acl == null) {
                throw new IOException("filesystem exposes neither POSIX permissions nor ACLs");
            }
            AclEntry owner = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(Files.getOwner(file))
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .build();
            acl.setAcl(List.of(owner));
            secured = true;
        } finally {
            if (!secured) {
                Files.deleteIfExists(file);
            }
        }
    }
}
