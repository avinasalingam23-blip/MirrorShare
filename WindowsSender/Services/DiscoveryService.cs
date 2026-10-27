using System.Net;
using System.Net.Sockets;
using System.Text;
using MirrorShare.Models;

namespace MirrorShare.Services;

public sealed class DiscoveryService : IAsyncDisposable
{
    public const int DiscoveryPort = 5002;
    private const string DiscoveryRequest = "WIRELESS_DISPLAY_DISCOVER";
    private const string DiscoveryResponse = "WIRELESS_DISPLAY_RECEIVER";

    private CancellationTokenSource? _loopCts;
    private Task? _loopTask;

    public event EventHandler<IReadOnlyList<ReceiverDevice>>? DevicesChanged;

    public async Task<IReadOnlyList<ReceiverDevice>> ScanAsync(CancellationToken cancellationToken = default)
    {
        var found = new Dictionary<string, ReceiverDevice>(StringComparer.OrdinalIgnoreCase);

        using var udp = new UdpClient(AddressFamily.InterNetwork);
        udp.EnableBroadcast = true;
        udp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        udp.Client.Bind(new IPEndPoint(IPAddress.Any, 0));

        var payload = Encoding.UTF8.GetBytes(DiscoveryRequest);
        var broadcast = new IPEndPoint(IPAddress.Broadcast, DiscoveryPort);

        for (var i = 0; i < 3; i++)
        {
            await udp.SendAsync(payload, payload.Length, broadcast);
            await Task.Delay(120, cancellationToken);
        }

        var deadline = DateTime.UtcNow.AddSeconds(2.5);
        while (DateTime.UtcNow < deadline && !cancellationToken.IsCancellationRequested)
        {
            try
            {
                using var receiveCts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                receiveCts.CancelAfter(260);
                var result = await udp.ReceiveAsync(receiveCts.Token);
                var message = Encoding.UTF8.GetString(result.Buffer).Trim();

                if (!message.StartsWith(DiscoveryResponse + "|", StringComparison.Ordinal))
                    continue;

                var parts = message.Split('|');
                var ip = result.RemoteEndPoint.Address.ToString();
                var receiver = new ReceiverDevice
                {
                    Name = parts.Length > 1 && !string.IsNullOrWhiteSpace(parts[1]) ? parts[1] : "Android TV",
                    Ip = ip,
                    VideoPort = ParsePort(parts, 2, 5000),
                    AudioPort = ParsePort(parts, 3, 5001),
                    ReceiverId = parts.Length > 4 ? parts[4] : ip,
                    Version = parts.Length > 5 ? parts[5] : "",
                    Availability = parts.Length > 6 ? parts[6] : "available"
                };

                found[ip] = receiver;
            }
            catch (OperationCanceledException)
            {
                // Normal receive timeout; continue until the overall deadline.
            }
            catch (SocketException ex)
            {
                AppLogger.Warn($"Discovery receive warning: {ex.SocketErrorCode}");
            }
        }

        return found.Values.OrderBy(x => x.Name).Take(3).ToArray();
    }

    public void StartAutoScan(TimeSpan? interval = null)
    {
        StopAutoScan();
        _loopCts = new CancellationTokenSource();
        var token = _loopCts.Token;
        var delay = interval ?? TimeSpan.FromSeconds(8);

        _loopTask = Task.Run(async () =>
        {
            while (!token.IsCancellationRequested)
            {
                try
                {
                    var devices = await ScanAsync(token);
                    DevicesChanged?.Invoke(this, devices);
                }
                catch (OperationCanceledException)
                {
                    break;
                }
                catch (Exception ex)
                {
                    AppLogger.Error("Automatic discovery failed.", ex);
                }

                try
                {
                    await Task.Delay(delay, token);
                }
                catch (OperationCanceledException)
                {
                    break;
                }
            }
        }, token);
    }

    public void StopAutoScan()
    {
        if (_loopCts is null) return;
        try { _loopCts.Cancel(); } catch { }
        _loopCts.Dispose();
        _loopCts = null;
        _loopTask = null;
    }

    private static int ParsePort(string[] parts, int index, int fallback) =>
        parts.Length > index && int.TryParse(parts[index], out var port) ? port : fallback;

    public ValueTask DisposeAsync()
    {
        StopAutoScan();
        return ValueTask.CompletedTask;
    }
}
