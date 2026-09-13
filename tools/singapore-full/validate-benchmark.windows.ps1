param([Parameter(Mandatory=$true)][string]$RequestFile,[Parameter(Mandatory=$true)][string]$ReportFile)
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
Add-Type -TypeDefinition @"
using System;
using System.Text;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Threading;
using System.ComponentModel;

public static class BenchmarkJob {
 [StructLayout(LayoutKind.Sequential)] struct BasicLimits {
  public long ProcessTime, JobTime; public uint Flags;
  public UIntPtr MinWorkingSet, MaxWorkingSet; public uint ActiveLimit;
  public UIntPtr Affinity; public uint Priority, Scheduling;
 }
 [StructLayout(LayoutKind.Sequential)] struct IoCounters { public ulong R1,R2,R3,T1,T2,T3; }
 [StructLayout(LayoutKind.Sequential)] struct ExtendedLimits {
  public BasicLimits Basic; public IoCounters Io;
  public UIntPtr ProcessMemory, JobMemory, PeakProcessMemory, PeakJobMemory;
 }
 [StructLayout(LayoutKind.Sequential)] struct Accounting {
  public long User, Kernel, PeriodUser, PeriodKernel;
  public uint PageFaults, TotalProcesses, ActiveProcesses, TerminatedProcesses;
 }
 [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)] struct Startup {
  public int cb; public string Reserved, Desktop, Title;
  public uint X,Y,XSize,YSize,XChars,YChars,Fill,Flags;
  public short Show, ReservedBytes; public IntPtr ReservedData, StdIn, StdOut, StdErr;
 }
 [StructLayout(LayoutKind.Sequential)] struct ProcInfo { public IntPtr Process, Thread; public uint Id, ThreadId; }
 [DllImport("kernel32.dll",SetLastError=true)] static extern IntPtr CreateJobObject(IntPtr attrs,string name);
 [DllImport("kernel32.dll",SetLastError=true)] static extern bool SetInformationJobObject(IntPtr job,int kind,ref ExtendedLimits info,uint bytes);
 [DllImport("kernel32.dll",SetLastError=true)] static extern bool QueryInformationJobObject(IntPtr job,int kind,IntPtr info,uint bytes,IntPtr returned);
 [DllImport("kernel32.dll",SetLastError=true)] static extern bool AssignProcessToJobObject(IntPtr job,IntPtr process);
 [DllImport("kernel32.dll",SetLastError=true)] static extern bool IsProcessInJob(IntPtr process,IntPtr job,out bool inJob);
 [DllImport("kernel32.dll",SetLastError=true,CharSet=CharSet.Unicode)] static extern bool CreateProcess(string app,StringBuilder args,IntPtr pa,IntPtr ta,bool inherit,uint flags,IntPtr env,string cwd,ref Startup startup,out ProcInfo info);
 [DllImport("kernel32.dll",SetLastError=true)] static extern uint ResumeThread(IntPtr thread);
 [DllImport("kernel32.dll")] static extern bool TerminateJobObject(IntPtr job,uint exit);
 [DllImport("kernel32.dll")] static extern bool TerminateProcess(IntPtr process,uint exit);
 [DllImport("kernel32.dll")] static extern bool GetExitCodeProcess(IntPtr process,out uint exit);
 [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
 [DllImport("kernel32.dll")] static extern IntPtr GetStdHandle(int kind);
 static void Check(bool ok) { if(!ok) throw new Win32Exception(Marshal.GetLastWin32Error()); }
 static T Query<T>(IntPtr job,int kind) {
  int size=Marshal.SizeOf(typeof(T)); IntPtr buffer=Marshal.AllocHGlobal(size);
  try { Check(QueryInformationJobObject(job,kind,buffer,(uint)size,IntPtr.Zero)); return (T)Marshal.PtrToStructure(buffer,typeof(T)); }
  finally { Marshal.FreeHGlobal(buffer); }
 }
 public static string Quote(string arg) {
  StringBuilder b=new StringBuilder("\""); int slashes=0;
  foreach(char c in arg) {
   if(c=='\\') { slashes++; continue; }
   if(c=='"') { b.Append('\\',slashes*2+1); b.Append(c); slashes=0; continue; }
   b.Append('\\',slashes); slashes=0; b.Append(c);
  }
  b.Append('\\',slashes*2); b.Append('"'); return b.ToString();
 }
 public class Result {
  public double wallSeconds,cpuSeconds,userCpuSeconds,kernelCpuSeconds;
  public ulong peakSampledRssBytes,peakJobCommitBytes;
  public uint exitCode,totalProcesses;
  public bool timedOut,parentExited;
  public int sampleIntervalMs,samples,missedProcessSamples,processorOffset,logicalProcessorCount;
  public string affinityMask;
  public string rssMethod="Maximum non-atomic sum of owned process working sets; shared pages can be counted twice and short peaks may be missed";
 }
 static ulong Sample(IntPtr job,ref int missed) {
  int size=65544; IntPtr buffer=Marshal.AllocHGlobal(size);
  try {
   Check(QueryInformationJobObject(job,3,buffer,(uint)size,IntPtr.Zero));
   int count=Marshal.ReadInt32(buffer,4); ulong total=0;
   for(int i=0;i<count;i++) {
    long id=IntPtr.Size==8 ? Marshal.ReadInt64(buffer,8+i*8) : Marshal.ReadInt32(buffer,8+i*4);
    try {
     using(Process p=Process.GetProcessById(checked((int)id))) {
      bool belongs; if(IsProcessInJob(p.Handle,job,out belongs)&&belongs) {
       p.Refresh(); total+=(ulong)Math.Max(0,p.WorkingSet64);
      } else missed++;
     }
    } catch(ArgumentException) { missed++; } catch(InvalidOperationException) { missed++; } catch(Win32Exception) { missed++; }
   }
   return total;
  } finally { Marshal.FreeHGlobal(buffer); }
 }
 public static Result Run(string[] argv,string cwd,int timeout,int cores,ulong memory,int interval,int parentId,int processorOffset) {
  if(Environment.ProcessorCount>64 || cores<1 || processorOffset<2 || processorOffset+cores>Environment.ProcessorCount)
   throw new Exception("A single processor group with two spare logical processors is required");
  IntPtr job=CreateJobObject(IntPtr.Zero,null); Check(job!=IntPtr.Zero);
  ProcInfo process=new ProcInfo(); bool assigned=false;
  Process parent=null;
  try {
   parent=Process.GetProcessById(parentId);
   IntPtr parentHandle=parent.Handle;
   ExtendedLimits limits=new ExtendedLimits();
   limits.Basic.Flags=0x2000|0x200|0x10;
   ulong mask=0; for(int i=processorOffset;i<cores+processorOffset;i++) mask|=1UL<<i;
   limits.Basic.Affinity=(UIntPtr)mask; limits.JobMemory=(UIntPtr)memory;
   Check(SetInformationJobObject(job,9,ref limits,(uint)Marshal.SizeOf(typeof(ExtendedLimits))));
   Startup start=new Startup(); start.cb=Marshal.SizeOf(typeof(Startup)); start.Flags=0x100;
   start.StdIn=GetStdHandle(-10); start.StdOut=GetStdHandle(-11); start.StdErr=GetStdHandle(-12);
   StringBuilder command=new StringBuilder();
   foreach(string arg in argv) { if(command.Length>0) command.Append(' '); command.Append(Quote(arg)); }
   Check(CreateProcess(argv[0],command,IntPtr.Zero,IntPtr.Zero,true,0x08000004,IntPtr.Zero,cwd,ref start,out process));
   Check(AssignProcessToJobObject(job,process.Process)); assigned=true;
   Stopwatch watch=Stopwatch.StartNew();
   Check(ResumeThread(process.Thread)!=UInt32.MaxValue);
   Result result=new Result(); result.sampleIntervalMs=interval;
   result.processorOffset=processorOffset; result.logicalProcessorCount=cores; result.affinityMask="0x"+mask.ToString("x");
   while(true) {
    Accounting accounting=Query<Accounting>(job,1);
    result.peakSampledRssBytes=Math.Max(result.peakSampledRssBytes,Sample(job,ref result.missedProcessSamples)); result.samples++;
    if(accounting.ActiveProcesses==0) break;
    result.timedOut=watch.Elapsed.TotalSeconds>=timeout;
    result.parentExited=parent.HasExited;
    if(result.timedOut||result.parentExited) {
     Check(TerminateJobObject(job,124));
     Stopwatch drain=Stopwatch.StartNew();
     while(Query<Accounting>(job,1).ActiveProcesses>0 && drain.Elapsed.TotalSeconds<5) Thread.Sleep(10);
     if(Query<Accounting>(job,1).ActiveProcesses>0) throw new Exception("Owned job failed to drain within five seconds");
     break;
    }
    Thread.Sleep(interval);
   }
   result.wallSeconds=watch.Elapsed.TotalSeconds;
   Accounting final=Query<Accounting>(job,1); ExtendedLimits peak=Query<ExtendedLimits>(job,9);
   result.userCpuSeconds=final.User/10000000.0; result.kernelCpuSeconds=final.Kernel/10000000.0;
   result.cpuSeconds=result.userCpuSeconds+result.kernelCpuSeconds; result.totalProcesses=final.TotalProcesses;
   result.peakJobCommitBytes=peak.PeakJobMemory.ToUInt64();
   Check(GetExitCodeProcess(process.Process,out result.exitCode));
   return result;
  } finally {
   if(process.Process!=IntPtr.Zero && !assigned) TerminateProcess(process.Process,125);
   if(job!=IntPtr.Zero) CloseHandle(job);
   if(process.Thread!=IntPtr.Zero) CloseHandle(process.Thread);
   if(process.Process!=IntPtr.Zero) CloseHandle(process.Process);
   if(parent!=null) parent.Dispose();
  }
 }
}
"@
$request=Get-Content -Raw -LiteralPath $RequestFile | ConvertFrom-Json
$result=[BenchmarkJob]::Run([string[]]$request.argv,[string]$request.cwd,[int]$request.timeoutSeconds,
  [int]$request.maxCores,[uint64]$request.maxMemoryBytes,[int]$request.sampleIntervalMs,[int]$request.parentPid,[int]$request.processorOffset)
$result | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $ReportFile -Encoding UTF8
