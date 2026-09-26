# A Windows job owns the launcher and its descendants, including children whose
# parent exits early. Disarm only after successful dashboard handoff.
if (-not ('ButlerSetupJob' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
public sealed class ButlerSetupJob : IDisposable {
    [StructLayout(LayoutKind.Sequential)] struct Basic {
        public long ProcessTime, JobTime;
        public uint Flags;
        public UIntPtr Minimum, Maximum;
        public uint Active;
        public UIntPtr Affinity;
        public uint Priority, Scheduling;
    }
    [StructLayout(LayoutKind.Sequential)] struct Io {
        public ulong ReadOps, WriteOps, OtherOps, ReadBytes, WriteBytes, OtherBytes;
    }
    [StructLayout(LayoutKind.Sequential)] struct Extended {
        public Basic Basic;
        public Io Io;
        public UIntPtr ProcessMemory, JobMemory, PeakProcessMemory, PeakJobMemory;
    }
    [DllImport("kernel32.dll", SetLastError=true)] static extern IntPtr CreateJobObject(IntPtr a, string n);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool SetInformationJobObject(IntPtr h, int c, ref Extended v, uint size);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool AssignProcessToJobObject(IntPtr h, IntPtr p);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool CloseHandle(IntPtr h);
    IntPtr handle;
    public ButlerSetupJob() {
        handle=CreateJobObject(IntPtr.Zero, null);
        if(handle==IntPtr.Zero) throw new Win32Exception();
        try { SetLimit(0x2000); } catch { Dispose(); throw; }
    }
    void SetLimit(uint flags) {
        var value=new Extended(); value.Basic.Flags=flags;
        if(!SetInformationJobObject(handle,9,ref value,(uint)Marshal.SizeOf(value))) throw new Win32Exception();
    }
    public void Assign(System.Diagnostics.Process process) {
        if(!AssignProcessToJobObject(handle,process.Handle)) throw new Win32Exception();
    }
    public void Disarm() { SetLimit(0); }
    public void Dispose() { if(handle!=IntPtr.Zero) { CloseHandle(handle); handle=IntPtr.Zero; } }
}
'@
}
