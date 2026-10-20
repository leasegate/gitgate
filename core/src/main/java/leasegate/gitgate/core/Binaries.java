package leasegate.gitgate.core;

import datapotter.datahelper.Data;

/// The real programs gitgate stands in front of. `ssh` must be Windows' own OpenSSH: it is the one
/// that reaches an agent on a named pipe.
@Data
public final class Binaries extends Binaries_A {
    String git = "git";
    String gh = "gh";
    String ssh = "C:/Windows/System32/OpenSSH/ssh.exe";
}
