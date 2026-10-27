using System.Diagnostics;
using System.Windows;
using System.Windows.Controls;
using MirrorShare.Models;

namespace MirrorShare.Views;

public partial class SettingsWindow : Window
{
    public int SelectedMonitorIndex { get; private set; }
    public string SelectedProfileName { get; private set; }

    public SettingsWindow(IReadOnlyList<DisplayMonitor> monitors, int selectedMonitorIndex, string selectedProfileName)
    {
        InitializeComponent();
        MonitorCombo.ItemsSource = monitors;
        MonitorCombo.SelectedIndex = monitors.Count == 0 ? -1 : Math.Clamp(selectedMonitorIndex, 0, monitors.Count - 1);
        SelectedMonitorIndex = Math.Max(0, MonitorCombo.SelectedIndex);
        SelectedProfileName = selectedProfileName;

        foreach (var item in ProfileCombo.Items.OfType<ComboBoxItem>())
        {
            if (string.Equals(item.Content?.ToString(), selectedProfileName, StringComparison.OrdinalIgnoreCase))
            {
                ProfileCombo.SelectedItem = item;
                break;
            }
        }

        if (ProfileCombo.SelectedIndex < 0)
            ProfileCombo.SelectedIndex = 0;
    }

    private void OpenSound_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            Process.Start(new ProcessStartInfo("ms-settings:sound") { UseShellExecute = true });
        }
        catch { }
    }

    private void Save_Click(object sender, RoutedEventArgs e)
    {
        SelectedMonitorIndex = Math.Max(0, MonitorCombo.SelectedIndex);
        SelectedProfileName = (ProfileCombo.SelectedItem as ComboBoxItem)?.Content?.ToString() ?? "High Quality";
        DialogResult = true;
        Close();
    }

    private void Cancel_Click(object sender, RoutedEventArgs e)
    {
        DialogResult = false;
        Close();
    }
}
