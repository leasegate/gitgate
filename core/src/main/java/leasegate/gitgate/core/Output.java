package leasegate.gitgate.core;

import datapotter.datahelper.Data;

/// Where the one-line `ACCOUNT(id,name) ACTIVATEDBY(rule,kind)` notice goes: `stdout`, `stderr` or
/// `none`. `accountLine` is for every command, `noArgs` for a bare `git` or `gh`.
@Data
public final class Output extends Output_A {
    String accountLine = "stdout";
    String noArgs = "stdout";
}
