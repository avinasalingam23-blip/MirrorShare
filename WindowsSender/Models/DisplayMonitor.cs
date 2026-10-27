namespace MirrorShare.Models;

public sealed record DisplayMonitor(
    int Index,
    string Name,
    int Left,
    int Top,
    int Width,
    int Height,
    bool IsPrimary)
{
    public override string ToString() => $"{(IsPrimary ? "Primary" : "Display")} {Index + 1} — {Width}×{Height}";
}
