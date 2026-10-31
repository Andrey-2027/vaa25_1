package org.ipro.unidr.store;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.ipro.unidr.model.*;
import org.ipro.unidr.run.UniDrAccess;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.*;

/** Shared report definitions, not personal variants or results. No database mapping. */
public final class UniDrFileStore {
    private static final int MAX_BYTES = 600_000;
    private static final Set<String> KEYS = Set.of("fileVersion", "id", "author", "description", "definition");
    private static final Map<Path,Object> LOCKS = new ConcurrentHashMap<>();
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UniDrFileStore.class);
    private final Path directory;
    private final UniDrDefinitionJsonCodec codec;
    private final UniDrAccess access;
    private final ObjectMapper mapper = JsonMapper.builder(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(40).maxStringLength(128_000).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public record Entry(long id, String name, String description, String author, String targetEntityClass) {}
    public record Saved(long id, String revision, String description, String author, UniDrDefinition definition) {}
    public record ImportPreview(String description, String originalAuthor, UniDrDefinition definition) {}
    private record Document(Entry entry, String revision, JsonNode definition) {}
    public static final class StoreException extends RuntimeException {
        StoreException(String message) { super(message); }
        StoreException(String message, Throwable cause) { super(message, cause); }
    }
    public UniDrFileStore(Path directory, UniDrDefinitionJsonCodec codec, UniDrAccess access) {
        this.directory = directory.toAbsolutePath().normalize();
        this.codec = Objects.requireNonNull(codec); this.access = Objects.requireNonNull(access);
    }
    public List<Entry> list(String search) {
        access.requireUser();
        return documents().stream().map(Document::entry)
            .filter(entry -> search == null || search.isBlank() || (entry.name()+" "+entry.description()).toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT)))
            .sorted(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }
    /** Reports explicitly bound to an entity type — the print list of that registry. */
    public List<Entry> findPrintable(String entityClass) {
        access.requireUser();
        if (entityClass == null || entityClass.isBlank()) return List.of();
        String target = entityClass.strip();
        return documents().stream().map(Document::entry)
            .filter(entry -> target.equals(entry.targetEntityClass()))
            .sorted(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }
    public Saved load(long id) {
        access.requireUser();
        var document = read(path(id));
        return saved(document, codec.decode(document.definition().toString()));
    }
    public Saved create(UniDrDefinition definition, String description) {
        return write(null, null, definition, description);
    }
    public Saved update(long id, String revision, UniDrDefinition definition, String description) {
        return write(id, revision, definition, description);
    }
    public Saved copy(long id,String title) {
        var original=load(id);
        return create(original.definition().withTitle(title),original.description());
    }
    public String currentUser(){return access.requireUser();}
    public String exportJson(long id) {
        access.requireUser();
        var document=read(path(id));
        // Validate the definition too: malformed stored files are never exported as valid reports.
        var definition=codec.decode(document.definition().toString());
        var root=mapper.createObjectNode();root.put("fileVersion",1);root.put("id",id);
        root.put("author",document.entry().author());root.put("description",document.entry().description());
        try {root.set("definition",mapper.readTree(codec.encode(definition)));return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);}
        catch(IOException failed){throw new StoreException("Не удалось экспортировать отчёт",failed);}
    }
    public ImportPreview inspectImport(String json) {
        access.requireUser();
        if(json==null || json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>MAX_BYTES)throw new StoreException("Файл отчёта превышает допустимый размер");
        try {
            var root=mapper.readTree(json);validateDocument(root,null);
            return new ImportPreview(root.path("description").asText(),root.path("author").asText(),codec.decode(root.path("definition").toString()));
        } catch(IOException failed){throw new StoreException("Некорректный JSON-файл отчёта",failed);}
    }
    public Saved importJson(String json,String title) {
        var preview=inspectImport(json);
        return create(preview.definition().withTitle(title),preview.description());
    }
    public void delete(long id,String expectedRevision) {
        String user=access.requireUser();
        synchronized(LOCKS.computeIfAbsent(directory,ignored->new Object())) {
            try {
                if(!ensureDirectory(false))throw new StoreException("JSON-файл отчёта не найден");
                var lockPath=directory.resolve(".write.lock");
                if(Files.isSymbolicLink(lockPath))throw new StoreException("Небезопасный файл блокировки хранилища");
                try(var channel=FileChannel.open(lockPath,CREATE,WRITE,NOFOLLOW_LINKS);var lock=channel.lock()) {
                    access.requireUser();var current=read(path(id));
                    if(!current.entry().author().equals(user))throw new StoreException("Удалять отчёт может только его автор");
                    if(!current.revision().equals(expectedRevision))throw new StoreException("Отчёт изменён. Обновите каталог и подтвердите удаление заново");
                    Files.delete(path(id));
                }
            } catch(IOException failed){throw new StoreException("Не удалось удалить JSON-файл отчёта",failed);}
        }
    }
    private Saved write(Long id, String expectedRevision, UniDrDefinition definition, String description) {
        String user = access.requireUser();
        if (definition != null && definition.title() != null) definition = definition.withTitle(definition.title().strip());
        // Validate before touching the filesystem. Query metadata/access checks still apply.
        String json = codec.encode(definition);
        String note = description == null ? "" : description.strip();
        if (note.length() > 1000) throw new StoreException("Описание должно содержать не больше 1000 символов");
        synchronized (LOCKS.computeIfAbsent(directory, ignored -> new Object())) {
            try {
                ensureDirectory(true);
                Path lockPath = directory.resolve(".write.lock");
                if (Files.isSymbolicLink(lockPath)) throw new StoreException("Небезопасный файл блокировки хранилища");
                try (var channel = FileChannel.open(lockPath, CREATE, WRITE, NOFOLLOW_LINKS); var lock = channel.lock()) {
                    access.requireUser();
                    long targetId;
                    if (id != null) {
                        var current = read(path(id));
                        if (!current.entry().author().equals(user)) throw new StoreException("Изменять отчёт может его автор. Используйте «Сохранить как»");
                        if (!current.revision().equals(expectedRevision)) throw new StoreException("Отчёт изменён в другой вкладке или в файле. Откройте его заново либо используйте «Сохранить как»");
                        targetId = id;
                    } else {
                        do { targetId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE; }
                        while (targetId == 0 || Files.exists(path(targetId), NOFOLLOW_LINKS));
                    }
                    for (var existing : documents())
                        if (existing.entry().id() != targetId && existing.entry().name().equalsIgnoreCase(definition.title().strip()))
                            throw new StoreException("Отчёт с таким названием уже существует. Укажите другое название");
                    ObjectNode root = mapper.createObjectNode();
                    root.put("fileVersion", 1); root.put("id", targetId); root.put("author", user); root.put("description", note);
                    root.set("definition", mapper.readTree(json));
                    byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
                    if (bytes.length > MAX_BYTES) throw new StoreException("Настройка превышает допустимый размер файла");
                    Path destination = path(targetId), temp = Files.createTempFile(directory, ".unidr-", ".tmp");
                    try {
                        try (var output = FileChannel.open(temp, WRITE, TRUNCATE_EXISTING, NOFOLLOW_LINKS)) {
                            var buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) output.write(buffer); output.force(true);
                        }
                        if (Files.isSymbolicLink(destination)) throw new StoreException("Файл отчёта не должен быть символической ссылкой");
                        Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } finally { Files.deleteIfExists(temp); }
                    return new Saved(targetId, digest(bytes), note, user, definition);
                }
            } catch (IOException failed) { throw new StoreException("Не удалось сохранить JSON-файл отчёта. Проверьте доступность папки хранилища", failed); }
        }
    }
    private List<Document> documents() {
        try {
            if (!ensureDirectory(false)) return List.of();
            List<Document> result = new ArrayList<>();
            try (var files = Files.newDirectoryStream(directory, "*.json")) {
                for (var file : files) {
                    if (!file.getFileName().toString().matches("[1-9][0-9]{0,18}\\.json")) continue;
                    try { result.add(read(file)); }
                    catch (StoreException invalid) { log.warn("UniDR: файл {} пропущен: {}", file.getFileName(), invalid.getMessage()); }
                }
            }
            return result;
        } catch (IOException failed) { throw new StoreException("Не удалось прочитать каталог JSON-отчётов", failed); }
    }
    private boolean ensureDirectory(boolean create) throws IOException {
        if (create) {
            Path ancestor = directory;
            while (ancestor != null && !Files.exists(ancestor, NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
            if (ancestor != null && (!Files.isDirectory(ancestor, NOFOLLOW_LINKS) || !ancestor.toRealPath().equals(ancestor)))
                throw new StoreException("Хранилище отчётов должно быть обычной папкой без символических ссылок");
            Files.createDirectories(directory);
        }
        if (!Files.exists(directory, NOFOLLOW_LINKS)) return false;
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS) || !directory.toRealPath().equals(directory))
            throw new StoreException("Хранилище отчётов должно быть обычной папкой без символических ссылок");
        return true;
    }
    private Path path(long id) {
        if (id <= 0) throw new StoreException("Некорректный идентификатор отчёта");
        return directory.resolve(id + ".json");
    }
    private Document read(Path file) {
        try {
            if (!ensureDirectory(false) || !Files.isRegularFile(file, NOFOLLOW_LINKS)) throw new StoreException("JSON-файл отчёта не найден");
            byte[] bytes;
            try (var input = Files.newInputStream(file, READ, NOFOLLOW_LINKS)) { bytes = input.readNBytes(MAX_BYTES + 1); }
            if (bytes.length > MAX_BYTES) throw new StoreException("Файл отчёта превышает допустимый размер");
            var root = mapper.readTree(bytes);
            validateDocument(root,file);
            var definition = root.path("definition");
            String targetEntityClass = definition.path("targetEntityClass").isTextual() ? definition.path("targetEntityClass").asText() : null;
            var entry = new Entry(root.path("id").longValue(), definition.path("title").asText(), root.path("description").asText(), root.path("author").asText(), targetEntityClass);
            return new Document(entry, digest(bytes), root.path("definition"));
        } catch (IOException failed) { throw new StoreException("Не удалось прочитать JSON-файл отчёта", failed); }
    }
    private void validateDocument(JsonNode root,Path file) {
            if (root == null || !root.isObject()) throw new StoreException("Некорректный JSON-файл отчёта");
            Set<String> names = new HashSet<>(); root.fieldNames().forEachRemaining(names::add);
            if (!names.equals(KEYS) || !root.path("fileVersion").isIntegralNumber() || !root.path("fileVersion").canConvertToInt() || root.path("fileVersion").intValue() != 1
                || !root.path("id").isIntegralNumber() || !root.path("id").canConvertToLong() || root.path("id").longValue() <= 0
                || file!=null && !file.getFileName().toString().equals(root.path("id").longValue() + ".json")
                || !root.path("author").isTextual() || root.path("author").asText().isBlank() || root.path("author").asText().length() > 200
                || !root.path("description").isTextual() || root.path("description").asText().length() > 1000
                || !root.path("definition").isObject() || !root.path("definition").path("title").isTextual()
                || root.path("definition").path("title").asText().isBlank() || root.path("definition").path("title").asText().length() > 200
                || root.path("definition").hasNonNull("targetEntityClass") && (!root.path("definition").get("targetEntityClass").isTextual()
                    || root.path("definition").get("targetEntityClass").asText().length() > 200))
                throw new StoreException("Некорректная структура или версия JSON-файла отчёта");
    }
    private Saved saved(Document document, UniDrDefinition definition) {
        var entry = document.entry(); return new Saved(entry.id(), document.revision(), entry.description(), entry.author(), definition);
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
