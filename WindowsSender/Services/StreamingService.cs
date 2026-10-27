using System.IO;
using System.Diagnostics;
using MirrorShare.Models;

namespace MirrorShare.Services;

public sealed class StreamingService : IDisposable
{
    public const int DefaultVideoPort = 5000;
    public const int DefaultAudioPort = 5001;

    private Process? _videoProcess;
    private Process? _audioProcess;

    public bool IsCasting => _videoProcess is { HasExited: false };
    public bool IsAudioActive => _audioProcess is { HasExited: false };
    public string ActiveEncoderDisplayName { get; private set; } = "Detecting...";
    public string? FfmpegPath { get; private set; }
    public string LastError { get; private set; } = "";

    public event EventHandler? StreamStopped;

    public StreamingService()
    {
        FfmpegPath = FindFfmpeg();
    }

    public async Task<string> DetectBestEncoderAsync(CancellationToken cancellationToken = default)
    {
        FfmpegPath = FindFfmpeg();
        if (FfmpegPath is null)
        {
            ActiveEncoderDisplayName = "FFmpeg not found";
            return ActiveEncoderDisplayName;
        }

        var encoders = await ReadEncodersAsync(cancellationToken);
        if (encoders.Contains("h264_nvenc", StringComparison.OrdinalIgnoreCase))
            ActiveEncoderDisplayName = "NVIDIA NVENC";
        else if (encoders.Contains("h264_qsv", StringComparison.OrdinalIgnoreCase))
            ActiveEncoderDisplayName = "Intel Quick Sync";
        else if (encoders.Contains("h264_amf", StringComparison.OrdinalIgnoreCase))
            ActiveEncoderDisplayName = "AMD AMF";
        else
            ActiveEncoderDisplayName = "CPU x264";

        return ActiveEncoderDisplayName;
    }

    public async Task<StreamStartResult> StartAsync(
        ReceiverDevice receiver,
        DisplayMonitor monitor,
        StreamProfile profile,
        CancellationToken cancellationToken = default)
    {
        Stop();
        LastError = "";
        FfmpegPath = FindFfmpeg();
        if (FfmpegPath is null)
            return StreamStartResult.Fail("ffmpeg.exe was not found. Put ffmpeg.exe beside MirrorShare.exe.");

        var available = await ReadEncodersAsync(cancellationToken);
        var candidates = new List<(string Encoder, string Display)>();
        if (available.Contains("h264_nvenc", StringComparison.OrdinalIgnoreCase)) candidates.Add(("h264_nvenc", "NVIDIA NVENC"));
        if (available.Contains("h264_qsv", StringComparison.OrdinalIgnoreCase)) candidates.Add(("h264_qsv", "Intel Quick Sync"));
        if (available.Contains("h264_amf", StringComparison.OrdinalIgnoreCase)) candidates.Add(("h264_amf", "AMD AMF"));
        candidates.Add(("libx264", "CPU x264"));

        Exception? lastException = null;
        string? lastEncoderError = null;

        foreach (var candidate in candidates.DistinctBy(x => x.Encoder))
        {
            cancellationToken.ThrowIfCancellationRequested();
            try
            {
                var video = CreateFfmpegProcess(BuildVideoArgs(
                    receiver.Ip,
                    receiver.VideoPort <= 0 ? DefaultVideoPort : receiver.VideoPort,
                    monitor,
                    profile,
                    candidate.Encoder),
                    $"video-{candidate.Encoder}");

                video.Start();
                video.BeginErrorReadLine();

                await Task.Delay(1300, cancellationToken);
                if (video.HasExited)
                {
                    lastEncoderError = $"{candidate.Display} could not start on this laptop or the TV connection was unavailable.";
                    SafeDisposeProcess(video);
                    continue;
                }

                video.EnableRaisingEvents = true;
                video.Exited += (_, _) => StreamStopped?.Invoke(this, EventArgs.Empty);
                _videoProcess = video;
                ActiveEncoderDisplayName = candidate.Display;
                AppLogger.Info($"Video stream started with {candidate.Display} to {receiver.Ip}.");
                break;
            }
            catch (Exception ex)
            {
                lastException = ex;
                AppLogger.Error($"Video encoder attempt failed: {candidate.Display}", ex);
            }
        }

        if (_videoProcess is null)
        {
            LastError = lastException?.Message ?? lastEncoderError ?? "Unable to start the video stream.";
            return StreamStartResult.Fail(LastError);
        }

        var audioActive = false;
        try
        {
            var audio = CreateFfmpegProcess(BuildAudioArgs(
                receiver.Ip,
                receiver.AudioPort <= 0 ? DefaultAudioPort : receiver.AudioPort),
                "audio");
            audio.Start();
            audio.BeginErrorReadLine();
            await Task.Delay(650, cancellationToken);

            if (!audio.HasExited)
            {
                _audioProcess = audio;
                audioActive = true;
                AppLogger.Info("Audio stream started using VB-Audio Virtual Cable.");
            }
            else
            {
                SafeDisposeProcess(audio);
                AppLogger.Warn("Audio process exited. VB-CABLE may not be installed/routed to CABLE Input.");
            }
        }
        catch (Exception ex)
        {
            AppLogger.Error("Audio stream could not start.", ex);
        }

        return StreamStartResult.Success(ActiveEncoderDisplayName, audioActive);
    }

    public void Stop()
    {
        StopProcess(ref _audioProcess);
        StopProcess(ref _videoProcess);
    }

    private IReadOnlyList<string> BuildVideoArgs(
        string ip,
        int port,
        DisplayMonitor monitor,
        StreamProfile profile,
        string encoder)
    {
        var args = new List<string>
        {
            "-hide_banner", "-loglevel", "warning",
            "-f", "gdigrab",
            "-framerate", profile.Fps.ToString(),
            "-draw_mouse", "1",
            "-offset_x", monitor.Left.ToString(),
            "-offset_y", monitor.Top.ToString(),
            "-video_size", $"{monitor.Width}x{monitor.Height}",
            "-i", "desktop"
        };

        if (monitor.Width != profile.Width || monitor.Height != profile.Height)
        {
            args.AddRange(new[]
            {
                "-vf",
                $"scale={profile.Width}:{profile.Height}:force_original_aspect_ratio=decrease:flags=bicubic," +
                $"pad={profile.Width}:{profile.Height}:(ow-iw)/2:(oh-ih)/2,fps={profile.Fps}"
            });
        }

        switch (encoder)
        {
            case "h264_nvenc":
                args.AddRange(new[]
                {
                    "-c:v", "h264_nvenc",
                    "-preset", "p3",
                    "-tune", "ull",
                    "-zerolatency", "1",
                    "-rc", "cbr",
                    "-b:v", profile.NvencBitrate,
                    "-maxrate", profile.NvencBitrate,
                    "-bufsize", profile.BufferSize,
                    "-g", profile.Fps.ToString(),
                    "-bf", "0",
                    "-slices", "1",
                    "-rc-lookahead", "0",
                    "-profile:v", "high"
                });
                break;

            case "h264_qsv":
                args.AddRange(new[]
                {
                    "-c:v", "h264_qsv",
                    "-preset", "veryfast",
                    "-b:v", profile.HardwareBitrate,
                    "-maxrate", profile.NvencBitrate,
                    "-bufsize", "2M",
                    "-g", profile.Fps.ToString(),
                    "-bf", "0",
                    "-look_ahead", "0"
                });
                break;

            case "h264_amf":
                args.AddRange(new[]
                {
                    "-c:v", "h264_amf",
                    "-usage", "ultralowlatency",
                    "-quality", "speed",
                    "-b:v", profile.HardwareBitrate,
                    "-maxrate", profile.NvencBitrate,
                    "-bufsize", "2M",
                    "-g", profile.Fps.ToString(),
                    "-bf", "0"
                });
                break;

            default:
                args.AddRange(new[]
                {
                    "-c:v", "libx264",
                    "-preset", "ultrafast",
                    "-tune", "zerolatency",
                    "-b:v", profile.CpuBitrate,
                    "-maxrate", profile.CpuBitrate,
                    "-bufsize", "1M",
                    "-g", profile.Fps.ToString(),
                    "-bf", "0"
                });
                break;
        }

        args.AddRange(new[]
        {
            "-pix_fmt", "yuv420p",
            "-fps_mode", "cfr",
            "-color_primaries", "bt709",
            "-color_trc", "bt709",
            "-colorspace", "bt709",
            "-an",
            "-bsf:v", "dump_extra=freq=keyframe",
            "-f", "h264",
            $"tcp://{ip}:{port}?tcp_nodelay=1"
        });

        return args;
    }

    private static IReadOnlyList<string> BuildAudioArgs(string ip, int port) => new[]
    {
        "-hide_banner", "-loglevel", "warning",
        "-thread_queue_size", "64",
        "-f", "dshow",
        "-audio_buffer_size", "20",
        "-i", "audio=CABLE Output (VB-Audio Virtual Cable)",
        "-vn",
        "-acodec", "pcm_s16le",
        "-ar", "44100",
        "-ac", "2",
        "-f", "s16le",
        $"tcp://{ip}:{port}?tcp_nodelay=1"
    };

    private Process CreateFfmpegProcess(IReadOnlyList<string> args, string logPrefix)
    {
        if (FfmpegPath is null) throw new FileNotFoundException("ffmpeg.exe not found.");

        var psi = new ProcessStartInfo
        {
            FileName = FfmpegPath,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardError = true,
            RedirectStandardOutput = false,
            WorkingDirectory = Path.GetDirectoryName(FfmpegPath) ?? AppContext.BaseDirectory
        };

        foreach (var arg in args)
            psi.ArgumentList.Add(arg);

        var process = new Process { StartInfo = psi };
        process.ErrorDataReceived += (_, e) =>
        {
            if (!string.IsNullOrWhiteSpace(e.Data))
                AppLogger.Info($"FFmpeg[{logPrefix}] {e.Data}");
        };
        return process;
    }

    private async Task<string> ReadEncodersAsync(CancellationToken cancellationToken)
    {
        if (FfmpegPath is null) return "";
        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = FfmpegPath,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true
            };
            psi.ArgumentList.Add("-hide_banner");
            psi.ArgumentList.Add("-encoders");

            using var process = Process.Start(psi)!;
            var outputTask = process.StandardOutput.ReadToEndAsync(cancellationToken);
            var errorTask = process.StandardError.ReadToEndAsync(cancellationToken);
            await process.WaitForExitAsync(cancellationToken);
            return (await outputTask) + Environment.NewLine + (await errorTask);
        }
        catch (Exception ex)
        {
            AppLogger.Error("Failed to query FFmpeg encoders.", ex);
            return "";
        }
    }

    private static string? FindFfmpeg()
    {
        var candidates = new[]
        {
            Path.Combine(AppContext.BaseDirectory, "ffmpeg.exe"),
            Path.Combine(Environment.CurrentDirectory, "ffmpeg.exe")
        };

        foreach (var candidate in candidates)
            if (File.Exists(candidate)) return candidate;

        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = "where.exe",
                Arguments = "ffmpeg",
                UseShellExecute = false,
                RedirectStandardOutput = true,
                CreateNoWindow = true
            };
            using var process = Process.Start(psi);
            var first = process?.StandardOutput.ReadLine();
            process?.WaitForExit(1500);
            if (!string.IsNullOrWhiteSpace(first) && File.Exists(first)) return first;
        }
        catch { }

        return null;
    }

    private static void StopProcess(ref Process? process)
    {
        if (process is null) return;
        try
        {
            if (!process.HasExited)
            {
                process.Kill(entireProcessTree: true);
                process.WaitForExit(1500);
            }
        }
        catch { }
        finally
        {
            SafeDisposeProcess(process);
            process = null;
        }
    }

    private static void SafeDisposeProcess(Process process)
    {
        try { process.Dispose(); } catch { }
    }

    public void Dispose() => Stop();
}

public sealed record StreamStartResult(bool Started, string Encoder, bool AudioActive, string Error)
{
    public static StreamStartResult Success(string encoder, bool audioActive) => new(true, encoder, audioActive, "");
    public static StreamStartResult Fail(string error) => new(false, "", false, error);
}
