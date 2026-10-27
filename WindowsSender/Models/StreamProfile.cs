namespace MirrorShare.Models;

public sealed record StreamProfile(
    string Name,
    int Width,
    int Height,
    int Fps,
    string NvencBitrate,
    string HardwareBitrate,
    string CpuBitrate,
    string BufferSize)
{
    public string ResolutionLabel => $"{Width} × {Height}";
    public string BadgeLabel => Height >= 1080 ? "1080P" : "720P";

    public static StreamProfile HighQuality { get; } =
        new("High Quality", 1920, 1080, 60, "18M", "16M", "12M", "1500k");

    public static StreamProfile Balanced { get; } =
        new("Balanced", 1920, 1080, 30, "14M", "12M", "10M", "1800k");

    public static StreamProfile LowLatency { get; } =
        new("Low Latency", 1280, 720, 60, "12M", "10M", "8M", "1000k");
}
