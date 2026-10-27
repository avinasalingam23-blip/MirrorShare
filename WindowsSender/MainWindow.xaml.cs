using System.Collections.ObjectModel;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Threading;
using MirrorShare.Models;
using MirrorShare.Services;
using MirrorShare.Views;
using Application = System.Windows.Application;
using Brush = System.Windows.Media.Brush;
using Brushes = System.Windows.Media.Brushes;
using Color = System.Windows.Media.Color;
using Button = System.Windows.Controls.Button;
using MessageBox = System.Windows.MessageBox;

namespace MirrorShare;

public partial class MainWindow : Window
{
    private readonly ObservableCollection<ReceiverDevice> _receivers = new();
    private readonly DiscoveryService _discovery = new();
    private readonly StreamingService _streaming = new();
    private readonly IReadOnlyList<DisplayMonitor> _monitors;
    private readonly DispatcherTimer _sessionTimer;
    private readonly AppSettings _settings;

    private ReceiverDevice? _selectedReceiver;
    private StreamProfile _profile = StreamProfile.HighQuality;
    private DateTime? _sessionStarted;
    private bool _scanBusy;
    private bool _closing;

    public MainWindow()
    {
        InitializeComponent();

        _settings = SettingsService.Load();
        _monitors = MonitorService.GetMonitors();
        if (_settings.MonitorIndex < 0 || _settings.MonitorIndex >= _monitors.Count)
            _settings.MonitorIndex = 0;

        _profile = ProfileFromName(_settings.ProfileName);
        ReceiverItems.ItemsSource = _receivers;

        _discovery.DevicesChanged += Discovery_DevicesChanged;
        _streaming.StreamStopped += Streaming_StreamStopped;

        _sessionTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        _sessionTimer.Tick += (_, _) => UpdateSessionClock();

        Loaded += MainWindow_Loaded;
        Closing += MainWindow_Closing;
    }

    private async void MainWindow_Loaded(object sender, RoutedEventArgs e)
    {
        ApplyProfileUi();
        NetworkFooterText.Text = $"Local IP {GetLocalIpv4()} • UDP 5002 discovery";
        _sessionTimer.Start();

        _ = DetectEncoderAsync();
        await ScanNowAsync();
        if (!_closing)
            _discovery.StartAutoScan(TimeSpan.FromSeconds(8));
    }

    private async Task DetectEncoderAsync()
    {
        var name = await _streaming.DetectBestEncoderAsync();
        await Dispatcher.InvokeAsync(() =>
        {
            EncoderText.Text = name;
            EncoderFooterText.Text = name;
            if (name.Contains("not found", StringComparison.OrdinalIgnoreCase))
            {
                SystemReadyText.Text = "FFMPEG MISSING";
                SystemReadyText.Foreground = new SolidColorBrush(Color.FromRgb(184, 86, 0));
                SystemReadyPill.Background = new SolidColorBrush(Color.FromRgb(255, 246, 224));
            }
        });
    }

    private async Task ScanNowAsync()
    {
        if (_scanBusy || _streaming.IsCasting || _closing) return;
        _scanBusy = true;
        ScanButton.IsEnabled = false;
        ScanButton.Content = "…";
        DiscoverySummary.Text = "Searching local network...";
        DiscoverySummary.Foreground = (Brush)Application.Current.Resources["BlueBrush"];

        try
        {
            var devices = await _discovery.ScanAsync();
            ApplyReceiverList(devices);
        }
        catch (Exception ex)
        {
            AppLogger.Error("Manual discovery scan failed.", ex);
            DiscoverySummary.Text = "Discovery failed — check network/firewall";
            DiscoverySummary.Foreground = (Brush)Application.Current.Resources["DangerBrush"];
        }
        finally
        {
            _scanBusy = false;
            ScanButton.IsEnabled = !_streaming.IsCasting;
            ScanButton.Content = "↻";
        }
    }

    private void Discovery_DevicesChanged(object? sender, IReadOnlyList<ReceiverDevice> devices)
    {
        if (_closing || _streaming.IsCasting) return;
        Dispatcher.BeginInvoke(() => ApplyReceiverList(devices));
    }

    private void ApplyReceiverList(IReadOnlyList<ReceiverDevice> devices)
    {
        var selectedIp = _selectedReceiver?.Ip;
        _receivers.Clear();
        foreach (var device in devices.Take(3))
            _receivers.Add(device);

        NoReceiverPanel.Visibility = _receivers.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        ReceiverItems.Visibility = _receivers.Count == 0 ? Visibility.Collapsed : Visibility.Visible;

        if (_receivers.Count == 0)
        {
            DiscoverySummary.Text = "No receiver found";
            DiscoverySummary.Foreground = (Brush)Application.Current.Resources["MutedBrush"];
            if (_selectedReceiver is null)
            {
                HeroSubtitle.Text = "Open the receiver app on your TV";
                LeftStatusText.Text = "Waiting for a receiver";
            }
        }
        else
        {
            DiscoverySummary.Text = $"{_receivers.Count} receiver{(_receivers.Count == 1 ? "" : "s")} found";
            DiscoverySummary.Foreground = (Brush)Application.Current.Resources["SuccessBrush"];
            LeftStatusText.Text = "Ready for receiver selection";

            if (selectedIp is not null)
            {
                var refreshed = _receivers.FirstOrDefault(x => x.Ip == selectedIp);
                if (refreshed is not null) _selectedReceiver = refreshed;
            }
        }
    }

    private async void Scan_Click(object sender, RoutedEventArgs e) => await ScanNowAsync();

    private void SelectReceiver_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button { DataContext: ReceiverDevice device })
            SelectReceiver(device);
    }

    private void ManualConnect_Click(object sender, RoutedEventArgs e)
    {
        var ip = ManualIpTextBox.Text.Trim();
        if (!IPAddress.TryParse(ip, out var address) || address.AddressFamily != AddressFamily.InterNetwork)
        {
            MessageBox.Show(this, "Enter a valid IPv4 address.", "Invalid IP", MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        SelectReceiver(new ReceiverDevice { Name = "Manual Receiver", Ip = ip });
    }

    private void SelectReceiver(ReceiverDevice device)
    {
        if (_streaming.IsCasting) return;
        _selectedReceiver = device;
        SelectedReceiverName.Text = device.Name;
        SelectedReceiverIp.Text = device.Ip;
        SelectedReceiverState.Text = "SELECTED";
        HeroSubtitle.Text = "Receiver selected • press START CASTING";
        LeftStatusText.Text = $"Selected: {device.Name}";
        SessionFooterText.Text = "Receiver selected";
        StartCastingButton.IsEnabled = true;
    }

    private async void StartCasting_Click(object sender, RoutedEventArgs e)
    {
        if (_selectedReceiver is null)
        {
            MessageBox.Show(this, "Select a receiver first or enter its IP address.", "Select receiver", MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }

        if (_monitors.Count == 0)
        {
            MessageBox.Show(this, "No Windows display was detected.", "Display unavailable", MessageBoxButton.OK, MessageBoxImage.Error);
            return;
        }

        var monitorIndex = Math.Clamp(_settings.MonitorIndex, 0, _monitors.Count - 1);
        var monitor = _monitors[monitorIndex];

        SetConnectingUi();
        _discovery.StopAutoScan();

        try
        {
            var result = await _streaming.StartAsync(_selectedReceiver, monitor, _profile);
            if (!result.Started)
            {
                SetReadyUi();
                MessageBox.Show(this,
                    result.Error + "\n\nConfirm the TV receiver is open, both devices are on the same network, and ports 5000/5001 are reachable.",
                    "Casting could not start",
                    MessageBoxButton.OK,
                    MessageBoxImage.Error);
                if (!_closing) _discovery.StartAutoScan(TimeSpan.FromSeconds(8));
                return;
            }

            _sessionStarted = DateTime.Now;
            EncoderText.Text = result.Encoder;
            EncoderFooterText.Text = result.Encoder;
            SetLiveUi(result.AudioActive);
        }
        catch (Exception ex)
        {
            AppLogger.Error("Start casting UI operation failed.", ex);
            SetReadyUi();
            MessageBox.Show(this, ex.Message, "Casting error", MessageBoxButton.OK, MessageBoxImage.Error);
            if (!_closing) _discovery.StartAutoScan(TimeSpan.FromSeconds(8));
        }
    }

    private void StopCasting_Click(object sender, RoutedEventArgs e) => StopCasting(userInitiated: true);

    private void StopCasting(bool userInitiated)
    {
        _streaming.Stop();
        _sessionStarted = null;
        SetReadyUi();
        SessionFooterText.Text = userInitiated ? "Ready to reconnect" : "Stream stopped";
        if (!_closing) _discovery.StartAutoScan(TimeSpan.FromSeconds(8));
    }

    private void Streaming_StreamStopped(object? sender, EventArgs e)
    {
        if (_closing) return;
        Dispatcher.BeginInvoke(() =>
        {
            if (_streaming.IsCasting) return;
            _sessionStarted = null;
            SetReadyUi();
            SessionFooterText.Text = "Ready to reconnect";
            _discovery.StartAutoScan(TimeSpan.FromSeconds(8));
        });
    }

    private void SetConnectingUi()
    {
        HeroPillText.Text = "CONNECTING";
        SystemReadyText.Text = "Connecting";
        HeroDot.Fill = (Brush)Application.Current.Resources["BlueBrush"];
        HeroSubtitle.Text = $"Opening low-latency stream to {_selectedReceiver!.Name}";
        SelectedReceiverState.Text = "CONNECTING";
        StartCastingButton.IsEnabled = false;
        StopCastingButton.IsEnabled = true;
        ScanButton.IsEnabled = false;
        StreamFooterText.Text = "Connecting";
        SessionFooterText.Text = "Connecting";
        RightReadyText.Text = "Connecting";
    }

    private void SetLiveUi(bool audioActive)
    {
        HeroPillText.Text = "LIVE";
        SystemReadyText.Text = "Casting";
        HeroDot.Fill = (Brush)Application.Current.Resources["SuccessBrush"];
        HeroSubtitle.Text = $"{_selectedReceiver!.Name}  •  {_selectedReceiver.Ip}";
        SelectedReceiverState.Text = "CASTING";
        StartCastingButton.IsEnabled = false;
        StopCastingButton.IsEnabled = true;
        VideoStatusText.Text = "Streaming";
        VideoStatusText.Foreground = (Brush)Application.Current.Resources["SuccessBrush"];
        AudioStatusText.Text = audioActive ? "Streaming" : "VB-CABLE not active";
        AudioStatusText.Foreground = audioActive
            ? (Brush)Application.Current.Resources["SuccessBrush"]
            : new SolidColorBrush(Color.FromRgb(194, 117, 0));
        RightReadyText.Text = "Live";
        StreamFooterText.Text = "Casting live";
        SessionFooterText.Text = _selectedReceiver.Name;
        SetProfileControlsEnabled(false);
    }

    private void SetReadyUi()
    {
        HeroPillText.Text = "READY";
        SystemReadyText.Text = "Ready to Cast";
        HeroDot.Fill = (Brush)Application.Current.Resources["SuccessBrush"];
        HeroSubtitle.Text = _selectedReceiver is null ? "Select a receiver and start casting" : "Ready to cast again";
        SelectedReceiverState.Text = _selectedReceiver is null ? "WAITING" : "SELECTED";
        StartCastingButton.IsEnabled = true;
        StopCastingButton.IsEnabled = false;
        ScanButton.IsEnabled = true;
        VideoStatusText.Text = "Not Streaming";
        VideoStatusText.Foreground = (Brush)Application.Current.Resources["MutedBrush"];
        AudioStatusText.Text = "Not Streaming";
        AudioStatusText.Foreground = (Brush)Application.Current.Resources["MutedBrush"];
        RightReadyText.Text = "Ready";
        StreamFooterText.Text = "Idle";
        SetProfileControlsEnabled(true);
    }

    private void HighQuality_Click(object sender, RoutedEventArgs e) => SetProfile(StreamProfile.HighQuality);
    private void Balanced_Click(object sender, RoutedEventArgs e) => SetProfile(StreamProfile.Balanced);
    private void LowLatency_Click(object sender, RoutedEventArgs e) => SetProfile(StreamProfile.LowLatency);

    private void SetProfile(StreamProfile profile)
    {
        if (_streaming.IsCasting) return;
        _profile = profile;
        _settings.ProfileName = profile.Name;
        SettingsService.Save(_settings);
        ApplyProfileUi();
    }

    private void ApplyProfileUi()
    {
        ResolutionStatusText.Text = _profile.ResolutionLabel;
        FpsStatusText.Text = $"{_profile.Fps} FPS";
        BadgeResolution.Text = $"▣  {_profile.BadgeLabel}";
        BadgeFps.Text = $"⌁  {_profile.Fps} FPS";
        BitrateText.Text = _profile.Name switch
        {
            "High Quality" => "18 Mbps",
            "Balanced" => "14 Mbps",
            _ => "12 Mbps"
        };

        ResetProfileButton(HighQualityButton);
        ResetProfileButton(BalancedButton);
        ResetProfileButton(LowLatencyButton);
        var selected = _profile.Name switch
        {
            "Balanced" => BalancedButton,
            "Low Latency" => LowLatencyButton,
            _ => HighQualityButton
        };
        selected.Background = (Brush)Application.Current.Resources["PrimaryGradient"];
        selected.Foreground = Brushes.White;
        selected.BorderThickness = new Thickness(0);
    }

    private static void ResetProfileButton(Button button)
    {
        button.ClearValue(Button.BackgroundProperty);
        button.ClearValue(Button.ForegroundProperty);
        button.ClearValue(Button.BorderThicknessProperty);
    }

    private void SetProfileControlsEnabled(bool enabled)
    {
        HighQualityButton.IsEnabled = enabled;
        BalancedButton.IsEnabled = enabled;
        LowLatencyButton.IsEnabled = enabled;
    }

    private void Settings_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new SettingsWindow(_monitors, _settings.MonitorIndex, _profile.Name) { Owner = this };
        if (dialog.ShowDialog() != true) return;

        _settings.MonitorIndex = dialog.SelectedMonitorIndex;
        _settings.ProfileName = dialog.SelectedProfileName;
        _profile = ProfileFromName(_settings.ProfileName);
        SettingsService.Save(_settings);
        ApplyProfileUi();
    }

    private void Logs_Click(object sender, RoutedEventArgs e)
    {
        Directory.CreateDirectory(AppLogger.LogDirectory);
        try
        {
            Process.Start(new ProcessStartInfo("explorer.exe", AppLogger.LogDirectory) { UseShellExecute = true });
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, ex.Message, "Open logs", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    private void Exit_Click(object sender, RoutedEventArgs e) => Close();

    private void UpdateSessionClock()
    {
        if (_sessionStarted is null)
        {
            SessionDurationText.Text = "00:00:00";
            return;
        }

        var elapsed = DateTime.Now - _sessionStarted.Value;
        SessionDurationText.Text = $"{(int)elapsed.TotalHours:00}:{elapsed.Minutes:00}:{elapsed.Seconds:00}";
    }

    private static StreamProfile ProfileFromName(string? name) => name switch
    {
        "Balanced" => StreamProfile.Balanced,
        "Low Latency" => StreamProfile.LowLatency,
        _ => StreamProfile.HighQuality
    };

    private static string GetLocalIpv4()
    {
        try
        {
            return Dns.GetHostAddresses(Dns.GetHostName())
                       .FirstOrDefault(a => a.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(a))
                       ?.ToString() ?? "Local network";
        }
        catch
        {
            return "Local network";
        }
    }

    private async void MainWindow_Closing(object? sender, System.ComponentModel.CancelEventArgs e)
    {
        _closing = true;
        _sessionTimer.Stop();
        _streaming.Stop();
        await _discovery.DisposeAsync();
        _streaming.Dispose();
    }
}
