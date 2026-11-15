package org.ipro.utr;

import org.ipro.filtergrid.projection.ColumnMeta;
import org.ipro.query.QueryLayoutReconciler;
import org.ipro.query.QueryProjectionCompiler;
import org.ipro.rls.RlsContext;
import org.ipro.rls.RlsCurrentUser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.ipro.utr.UtrStoreException.Code.*;

/** Хранилище документов UTR и источник записей для каталога отчётов. */
public final class UtrFileStore {
    private static final ConcurrentHashMap<Path, Object> LOCKS = new ConcurrentHashMap<>();
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(UtrFileStore.class);

    public record Entry(long id, String title, String description, String author, UtrVisibility visibility) {}
    public record Saved(Entry entry, String revision, UtrDocument document) {}
    public record Opened(Saved saved, boolean needsRepair, String diagnostic, List<String> missingColumns) {
        public Opened {
            missingColumns = List.copyOf(missingColumns);
        }
    }

    private record Stored(UtrDocumentCodec.File file, byte[] bytes) {
        Entry entry() {
            return new Entry(file.id(), file.document().title(), file.description(), file.author(), file.visibility());
        }

        Saved saved(UtrDocument document) {
            return new Saved(entry(), digest(bytes), document);
        }
    }

    private final Path directory;
    private final RlsCurrentUser users;
    private final QueryProjectionCompiler compiler;
    private final UtrDocumentCodec codec = new UtrDocumentCodec();

    public UtrFileStore(Path directory, RlsCurrentUser users, QueryProjectionCompiler compiler) {
        this.directory = directory.toAbsolutePath().normalize();
        this.users = Objects.requireNonNull(users);
        this.compiler = Objects.requireNonNull(compiler);
    }

    public List<Entry> list(String search) {
        String user = currentUser();
        String term = search == null ? "" : nameKey(search);
        return entries().stream().filter(entry -> visible(entry, user))
                .filter(entry -> nameKey(entry.title() + " " + entry.description()).contains(term))
                .sorted(Comparator.comparing(Entry::title, String.CASE_INSENSITIVE_ORDER).thenComparingLong(Entry::id))
                .toList();
    }

    public Entry find(long id) {
        return readVisible(id, currentUser()).entry();
    }

    public Opened open(long id) {
        String user = currentUser();
        var stored = readVisible(id, user);
        var document = stored.file().document();
        boolean author = stored.file().author().equals(user);
        try {
            var aliases = compiler.describe(document.query()).stream().map(ColumnMeta::key).collect(Collectors.toSet());
            var reconciled = QueryLayoutReconciler.reconcile(document.layout(), aliases);
            return new Opened(stored.saved(document.withLayout(reconciled.layout())), false, null,
                    author ? reconciled.missingColumns() : List.of());
        } catch (IllegalArgumentException | org.hibernate.HibernateException invalid) {
            return new Opened(stored.saved(document), true, author ? invalid.getMessage() : null, List.of());
        }
    }

    public Saved create(UtrDocument document, String description, UtrVisibility visibility) {
        String user = currentUser();
        return locked(() -> save(newId(), user, document, description, visibility));
    }

    public Saved update(long id, String expectedRevision, UtrDocument document, String description,
                        UtrVisibility visibility) {
        String user = currentUser();
        return locked(() -> {
            var stored = readVisible(id, user);
            if (!stored.file().author().equals(user)) {
                throw new UtrStoreException(FORBIDDEN, "Изменять отчёт может только его автор");
            }
            if (!digest(stored.bytes()).equals(expectedRevision)) {
                throw new UtrStoreException(CONFLICT, "Отчёт изменён в другом окне");
            }
            return save(id, user, document, description, visibility);
        });
    }

    public Saved copy(long id, String title) {
        String user = currentUser();
        return locked(() -> {
            var original = readVisible(id, user).file();
            return save(newId(), user, original.document().withTitle(title), original.description(), UtrVisibility.AUTHOR);
        });
    }

    public Saved copy(long id, UtrDocument draft, String description) {
        String user = currentUser();
        return locked(() -> {
            readVisible(id, user);
            return save(newId(), user, draft, description, UtrVisibility.AUTHOR);
        });
    }

    public String exportJson(long id) {
        return new String(readVisible(id, currentUser()).bytes(), UTF_8);
    }

    public Saved importJson(String json, String title) {
        String user = currentUser();
        if (json == null || json.length() > UtrDocumentCodec.MAX_BYTES) {
            throw new UtrStoreException(INVALID_FILE, "Некорректный размер файла отчёта");
        }
        var imported = codec.decode(json.getBytes(UTF_8));
        var document = title == null ? imported.document() : imported.document().withTitle(title);
        return locked(() -> save(newId(), user, document, imported.description(), UtrVisibility.AUTHOR));
    }

    private Saved save(long id, String user, UtrDocument document, String description, UtrVisibility visibility)
            throws IOException {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(visibility, "visibility");
        boolean duplicate = entries().stream().anyMatch(entry -> entry.id() != id && entry.visibility() == visibility
                && (visibility == UtrVisibility.ALL || entry.author().equals(user))
                && nameKey(entry.title()).equals(nameKey(document.title())));
        if (duplicate) throw new UtrStoreException(DUPLICATE_TITLE, "Отчёт с таким названием уже существует");

        var file = new UtrDocumentCodec.File(id, user, description == null ? "" : description.strip(), visibility, document);
        byte[] bytes = codec.encode(file);
        Path destination = path(id);
        if (Files.isSymbolicLink(destination)) throw new UtrStoreException(IO, "Недопустимая ссылка в хранилище UTR");
        Path temp = Files.createTempFile(directory, ".utr-", ".tmp");
        try {
            try (var output = FileChannel.open(temp, WRITE, NOFOLLOW_LINKS)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            Files.move(temp, destination, ATOMIC_MOVE, REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
        return new Stored(file, bytes).saved(document);
    }

    private List<Entry> entries() {
        try {
            if (!ensureDirectory(false)) return List.of();
            List<Entry> result = new ArrayList<>();
            try (var files = Files.newDirectoryStream(directory, "*.json")) {
                for (Path file : files) {
                    if (!file.getFileName().toString().matches("[1-9][0-9]{0,18}\\.json")) continue;
                    try {
                        result.add(read(file).entry());
                    } catch (UtrStoreException invalid) {
                        if (invalid.code() == IO) throw invalid;
                        LOG.warn("UTR: пропущен файл {}", file.getFileName());
                    }
                }
            }
            return result;
        } catch (IOException error) {
            throw new UtrStoreException(IO, "Не удалось прочитать каталог UTR", error);
        }
    }

    private Stored readVisible(long id, String user) {
        try {
            if (id < 1 || !ensureDirectory(false)) throw notFound();
            var stored = read(path(id));
            if (!visible(stored.entry(), user)) throw notFound();
            return stored;
        } catch (IOException error) {
            throw new UtrStoreException(IO, "Не удалось прочитать файл UTR", error);
        }
    }

    private Stored read(Path path) throws IOException {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) throw notFound();
        try (var input = Files.newInputStream(path, READ, NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(UtrDocumentCodec.MAX_BYTES + 1);
            var file = codec.decode(bytes);
            if (!path.equals(path(file.id()))) throw new UtrStoreException(INVALID_FILE, "ID отчёта не совпадает с именем файла");
            return new Stored(file, bytes);
        } catch (java.nio.file.NoSuchFileException missing) {
            throw notFound();
        }
    }

    private boolean ensureDirectory(boolean create) throws IOException {
        if (create) {
            Path ancestor = directory;
            while (!Files.exists(ancestor, NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
            requireDirectory(ancestor);
            Files.createDirectories(directory);
        }
        if (!Files.exists(directory, NOFOLLOW_LINKS)) return false;
        requireDirectory(directory);
        return true;
    }

    private static void requireDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path, NOFOLLOW_LINKS) || !path.toRealPath().equals(path)) {
            throw new UtrStoreException(IO, "Хранилище UTR должно быть обычной папкой без символических ссылок");
        }
    }

    private Saved locked(Write operation) {
        synchronized (LOCKS.computeIfAbsent(directory, ignored -> new Object())) {
            try {
                ensureDirectory(true);
                try (var channel = FileChannel.open(directory.resolve(".write.lock"), CREATE, WRITE, NOFOLLOW_LINKS);
                     var lock = channel.lock()) {
                    return operation.run();
                }
            } catch (IOException error) {
                throw new UtrStoreException(IO, "Не удалось записать файл UTR", error);
            }
        }
    }

    @FunctionalInterface
    private interface Write {
        Saved run() throws IOException;
    }

    private long newId() {
        long id;
        do {
            // Positive and exactly representable in JavaScript when used by the catalog.
            id = UUID.randomUUID().getMostSignificantBits() >>> 11;
        } while (id == 0 || Files.exists(path(id), NOFOLLOW_LINKS));
        return id;
    }

    private Path path(long id) {
        return directory.resolve(id + ".json");
    }

    private static boolean visible(Entry entry, String user) {
        return entry.visibility() == UtrVisibility.ALL || entry.author().equals(user);
    }

    private String currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken || RlsContext.isBypassed()) {
            throw new AccessDeniedException("Требуется вход для работы с табличным отчётом");
        }
        String user = users.requireAuthenticatedUsername();
        if (!user.equals(auth.getName())) throw new AccessDeniedException("Требуется вход для работы с табличным отчётом");
        return user;
    }

    private static String nameKey(String title) {
        return title.strip().toLowerCase(Locale.ROOT);
    }

    private static UtrStoreException notFound() {
        return new UtrStoreException(NOT_FOUND, "Табличный отчёт не найден");
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
