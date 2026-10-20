package leasegate.gitgate.app;

import java.nio.file.Path;

/// One credential the old tools kept in the open: a token, a key file, or both, and where it was.
record OldCredential(String account, String source, String token, Path keyFile) {}
