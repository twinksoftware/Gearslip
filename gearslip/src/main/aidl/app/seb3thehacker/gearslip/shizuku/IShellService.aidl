package app.seb3thehacker.gearslip.shizuku;

// The part of Gearslip that Shizuku runs as the shell user, with the same powers as adb.
interface IShellService {
    // Shizuku's own transaction code for ending the service process.
    void destroy() = 16777114;

    // Runs a shell command and returns its exit code. Only Gearslip's own fixed commands.
    int run(String command) = 1;
}
