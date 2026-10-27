using System.IO;
using System.Text;

namespace MirrorShare.Services;

public static class AppLogger
{
    private static readonly object Gate = new();
    public static string LogDirectory { get; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "Mirror Share",
        "Logs");

    public static string LogFile { get; } = Path.Combine(LogDirectory, "mirror-share.log");

    public static void Info(string message) => Write("INFO", message);
    public static void Warn(string message) => Write("WARN", message);
    public static void Error(string message, Exception? ex = null) =>
        Write("ERROR", ex is null ? message : $"{message}{Environment.NewLine}{ex}");

    private static void Write(string level, string message)
    {
        try
        {
            Directory.CreateDirectory(LogDirectory);
            var line = $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff}] [{level}] {message}{Environment.NewLine}";
            lock (Gate)
            {
                File.AppendAllText(LogFile, line, Encoding.UTF8);
            }
        }
        catch
        {
            // Logging must never stop the casting app.
        }
    }
}
