namespace MirrorShare.Models;

public sealed class ReceiverDevice
{
    public string Name { get; init; } = "Android TV";
    public string Ip { get; init; } = "";
    public int VideoPort { get; init; } = 5000;
    public int AudioPort { get; init; } = 5001;
    public string ReceiverId { get; init; } = "";
    public string Version { get; init; } = "";
    public string Availability { get; init; } = "available";

    public override string ToString() => $"{Name} ({Ip})";
}
