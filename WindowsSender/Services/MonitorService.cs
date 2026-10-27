using System.Runtime.InteropServices;
using MirrorShare.Models;

namespace MirrorShare.Services;

public static class MonitorService
{
    private const uint MonitorInfoFPrimary = 0x00000001;

    public static IReadOnlyList<DisplayMonitor> GetMonitors()
    {
        var monitors = new List<DisplayMonitor>();

        EnumDisplayMonitors(IntPtr.Zero, IntPtr.Zero,
            (hMonitor, _, _, _) =>
            {
                var info = new MonitorInfoEx
                {
                    cbSize = Marshal.SizeOf<MonitorInfoEx>()
                };

                if (GetMonitorInfo(hMonitor, ref info))
                {
                    var index = monitors.Count;
                    var width = info.rcMonitor.Right - info.rcMonitor.Left;
                    var height = info.rcMonitor.Bottom - info.rcMonitor.Top;
                    var primary = (info.dwFlags & MonitorInfoFPrimary) != 0;

                    monitors.Add(new DisplayMonitor(
                        index,
                        string.IsNullOrWhiteSpace(info.szDevice) ? $"Display {index + 1}" : info.szDevice,
                        info.rcMonitor.Left,
                        info.rcMonitor.Top,
                        width,
                        height,
                        primary));
                }

                return true;
            },
            IntPtr.Zero);

        // Put the primary monitor first so index 0 behaves naturally for users.
        return monitors
            .OrderByDescending(m => m.IsPrimary)
            .ThenBy(m => m.Left)
            .ThenBy(m => m.Top)
            .Select((m, i) => m with { Index = i })
            .ToList();
    }

    private delegate bool MonitorEnumProc(
        IntPtr hMonitor,
        IntPtr hdcMonitor,
        IntPtr lprcMonitor,
        IntPtr dwData);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool EnumDisplayMonitors(
        IntPtr hdc,
        IntPtr lprcClip,
        MonitorEnumProc lpfnEnum,
        IntPtr dwData);

    [DllImport("user32.dll", CharSet = CharSet.Auto)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetMonitorInfo(
        IntPtr hMonitor,
        ref MonitorInfoEx lpmi);

    [StructLayout(LayoutKind.Sequential)]
    private struct Rect
    {
        public int Left;
        public int Top;
        public int Right;
        public int Bottom;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Auto)]
    private struct MonitorInfoEx
    {
        public int cbSize;
        public Rect rcMonitor;
        public Rect rcWork;
        public uint dwFlags;

        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)]
        public string szDevice;
    }
}
