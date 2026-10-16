package app.phonemcp;

// Runs inside the Shizuku-started process (shell identity). Every method returns bytes and never throws:
// the first byte of readChunk/writeChunk results is 0 (ok, payload follows) or 1 (error, UTF-8 message follows).
interface IShellService {
    void destroy() = 16777114;

    // Packed: int exit, bool timedOut, bool truncated, int outLen, out, int errLen, err
    byte[] exec(String cmd, long timeoutMs, in byte[] stdin, long maxBytes) = 1;

    byte[] readChunk(String path, long offset, int length) = 2;

    byte[] writeChunk(String path, in byte[] data, boolean append) = 3;
}
