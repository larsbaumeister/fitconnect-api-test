package com.gfi.ozg.fitko.swing.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.gfi.ozg.fitko.swing.model.AppSettings;
import com.gfi.ozg.fitko.swing.model.Connection;
import com.gfi.ozg.fitko.swing.model.Preset;
import com.gfi.ozg.fitko.swing.model.SubmissionRecord;
import com.gfi.ozg.fitko.swing.model.Zustellpunkt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Everything the client persists, one JSON file per kind under the data
 * directory ({@code ~/.fitconnect-ui} unless {@code -Dfitconnect.ui.home} is
 * set).
 *
 * <p>Not thread-safe: read and mutate only on the Swing event thread. A
 * {@code saveX()} call serializes on the caller's thread and writes the file
 * on a background thread, so the UI never waits for the disk.
 */
public class AppData {

    public enum Kind { SETTINGS, CONNECTIONS, ZUSTELLPUNKTE, PRESETS, SUBMISSIONS }

    private static final Logger log = LoggerFactory.getLogger(AppData.class);

    private final Path directory;
    private final JsonFileStore<AppSettings> settingsStore;
    private final JsonFileStore<List<Connection>> connectionStore;
    private final JsonFileStore<List<Zustellpunkt>> zustellpunktStore;
    private final JsonFileStore<List<Preset>> presetStore;
    private final JsonFileStore<List<SubmissionRecord>> submissionStore;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "data-writer");
        t.setDaemon(true);
        return t;
    });
    private final List<Consumer<Kind>> listeners = new ArrayList<>();
    private final List<String> loadWarnings = new ArrayList<>();
    private Consumer<Exception> writeErrorHandler = e -> { };

    private AppSettings settings;
    private List<Connection> connections;
    private List<Zustellpunkt> zustellpunkte;
    private List<Preset> presets;
    private List<SubmissionRecord> submissions;

    public AppData(Path directory) {
        this.directory = directory;
        this.settingsStore = new JsonFileStore<>(directory.resolve("settings.json"),
                new TypeReference<>() { }, AppSettings::new, false);
        // Holds client secrets, so owner read/write only.
        this.connectionStore = new JsonFileStore<>(directory.resolve("connections.json"),
                new TypeReference<>() { }, ArrayList::new, true);
        this.zustellpunktStore = new JsonFileStore<>(directory.resolve("zustellpunkte.json"),
                new TypeReference<>() { }, ArrayList::new, false);
        this.presetStore = new JsonFileStore<>(directory.resolve("presets.json"),
                new TypeReference<>() { }, ArrayList::new, false);
        this.submissionStore = new JsonFileStore<>(directory.resolve("submissions.json"),
                new TypeReference<>() { }, ArrayList::new, false);
    }

    public static Path defaultDirectory() {
        String override = System.getProperty("fitconnect.ui.home");
        return override != null && !override.isBlank()
                ? Path.of(override)
                : Path.of(System.getProperty("user.home"), ".fitconnect-ui");
    }

    public AppData load() {
        settings = settingsStore.load();
        // Read from the status-poller thread too (connection lookup), so copy-on-write.
        connections = new CopyOnWriteArrayList<>(connectionStore.load());
        zustellpunkte = new ArrayList<>(zustellpunktStore.load());
        presets = new ArrayList<>(presetStore.load());
        submissions = new ArrayList<>(submissionStore.load());
        for (JsonFileStore<?> store : List.of(settingsStore, connectionStore, zustellpunktStore, presetStore, submissionStore)) {
            if (store.loadWarning() != null) {
                loadWarnings.add(store.loadWarning());
            }
        }
        return this;
    }

    public Path directory() {
        return directory;
    }

    public List<String> loadWarnings() {
        return loadWarnings;
    }

    public void onWriteError(Consumer<Exception> handler) {
        this.writeErrorHandler = handler;
    }

    public void addListener(Consumer<Kind> listener) {
        listeners.add(listener);
    }

    private void fire(Kind kind) {
        for (Consumer<Kind> listener : List.copyOf(listeners)) {
            listener.accept(kind);
        }
    }

    // --- settings ---------------------------------------------------------

    public AppSettings settings() {
        return settings;
    }

    public void saveSettings() {
        write(settingsStore, settings);
        fire(Kind.SETTINGS);
    }

    /** Returns the next {@code ${counter}} value and persists it. */
    public long nextCounter() {
        settings.counter++;
        write(settingsStore, settings);
        return settings.counter;
    }

    // --- connections ------------------------------------------------------

    public List<Connection> connections() {
        return connections;
    }

    public Optional<Connection> connection(String id) {
        return connections.stream().filter(c -> Objects.equals(c.id, id)).findFirst();
    }

    public void saveConnections() {
        write(connectionStore, connections);
        fire(Kind.CONNECTIONS);
    }

    // --- zustellpunkte ----------------------------------------------------

    public List<Zustellpunkt> zustellpunkte() {
        return zustellpunkte;
    }

    public Optional<Zustellpunkt> zustellpunkt(String id) {
        return zustellpunkte.stream().filter(z -> Objects.equals(z.id, id)).findFirst();
    }

    public void upsert(Zustellpunkt zustellpunkt) {
        replaceOrAdd(zustellpunkte, zustellpunkt, z -> z.id);
        saveZustellpunkte();
    }

    public void saveZustellpunkte() {
        write(zustellpunktStore, zustellpunkte);
        fire(Kind.ZUSTELLPUNKTE);
    }

    // --- presets ----------------------------------------------------------

    public List<Preset> presets() {
        return presets;
    }

    public Optional<Preset> preset(String id) {
        return presets.stream().filter(p -> Objects.equals(p.id, id)).findFirst();
    }

    public void upsert(Preset preset) {
        replaceOrAdd(presets, preset, p -> p.id);
        savePresets();
    }

    public void savePresets() {
        write(presetStore, presets);
        fire(Kind.PRESETS);
    }

    // --- submissions ------------------------------------------------------

    public List<SubmissionRecord> submissions() {
        return submissions;
    }

    public void add(SubmissionRecord record) {
        submissions.add(record);
        saveSubmissions();
    }

    public void saveSubmissions() {
        write(submissionStore, submissions);
        fire(Kind.SUBMISSIONS);
    }

    // ----------------------------------------------------------------------

    private static <E> void replaceOrAdd(List<E> list, E element, java.util.function.Function<E, String> id) {
        for (int i = 0; i < list.size(); i++) {
            if (Objects.equals(id.apply(list.get(i)), id.apply(element))) {
                list.set(i, element);
                return;
            }
        }
        list.add(element);
    }

    private <T> void write(JsonFileStore<T> store, T value) {
        byte[] content = store.serialize(value);
        writer.execute(() -> {
            try {
                store.write(content);
            } catch (RuntimeException e) {
                log.error("Could not save {}", store.file(), e);
                writeErrorHandler.accept(e);
            }
        });
    }

    /** Waits for pending writes - call on shutdown. */
    public void flush() {
        writer.shutdown();
        try {
            writer.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
