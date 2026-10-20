package leasegate.gitgate.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;

/// Reads `settings.toml`. A malformed file stops every git command, so the reason is put in the
/// first line rather than in a stack trace -- and the trap actually hit with TOML is a Windows path
/// in a double-quoted value, where every backslash is an escape; single quotes need none.
public final class SettingsStore {

    static final TomlMapper TOML = TomlMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    static final Pattern ENV = Pattern.compile("%([A-Za-z0-9_]+)%");

    public static Settings load() { return load(Places.settings()); }

    public static Settings load(Path file) {
        if (!Files.exists(file)) throw new IllegalStateException("gitgate has no settings yet: " + file
            + " -- write it by hand (see the README for its shape)");
        try {
            var s = TOML.readValue(file.toFile(), Settings.class);
            var b = s.getBinaries();
            b.setGit(expand(b.getGit()));
            b.setGh(expand(b.getGh()));
            b.setSsh(expand(b.getSsh()));
            for (var a : s.getAccount()) {
                a.setSsh(expand(a.getSsh()));
                a.setPaths(a.getPaths().stream().map(SettingsStore::expand).toList());
            }
            return s;
        } catch (Exception e) {
            var m = String.valueOf(e.getMessage());
            throw new IllegalStateException("cannot read " + file + ": " + (m.contains("escape")
                ? "a backslash in a double-quoted value is an escape in TOML; write Windows paths in single quotes, 'C:\\like\\this'" : m), e);
        }
    }

    /// `%NAME%` replaced by the environment variable, left as it is when there is none.
    static String expand(String s) {
        if (s == null) return null;
        var m = ENV.matcher(s);
        var out = new StringBuilder();
        while (m.find()) {
            var v = System.getenv(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(v != null ? v : m.group()));
        }
        return m.appendTail(out).toString();
    }

    public static String write(Settings s) {
        try { return TOML.writeValueAsString(s); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private SettingsStore() {}
}
