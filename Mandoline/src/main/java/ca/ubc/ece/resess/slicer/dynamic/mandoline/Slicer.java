package ca.ubc.ece.resess.slicer.dynamic.mandoline;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;


import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.io.FileUtils;

import ca.ubc.ece.resess.slicer.dynamic.core.accesspath.AccessPath;
import ca.ubc.ece.resess.slicer.dynamic.core.exceptions.InvalidCommandsException;
import ca.ubc.ece.resess.slicer.dynamic.core.framework.FrameworkModel;

import ca.ubc.ece.resess.slicer.dynamic.core.graph.Parser;
import ca.ubc.ece.resess.slicer.dynamic.core.graph.TraceStatement;
import ca.ubc.ece.resess.slicer.dynamic.core.instrumenter.Instrumenter;
import ca.ubc.ece.resess.slicer.dynamic.core.instrumenter.JimpleWriter;
import ca.ubc.ece.resess.slicer.dynamic.core.slicer.DynamicSlice;
import ca.ubc.ece.resess.slicer.dynamic.core.slicer.SlicePrinter;
import ca.ubc.ece.resess.slicer.dynamic.core.slicer.SlicingWorkingSet;
import ca.ubc.ece.resess.slicer.dynamic.core.sootcallgraphs.ThreadCalls;
import ca.ubc.ece.resess.slicer.dynamic.core.statements.StatementInstance;
import ca.ubc.ece.resess.slicer.dynamic.core.utils.AnalysisCache;
import ca.ubc.ece.resess.slicer.dynamic.core.utils.AnalysisLogger;
import ca.ubc.ece.resess.slicer.dynamic.core.utils.Constants;
import soot.Local;
import soot.SootClass;
import soot.SootMethod;
import soot.Type;
import soot.Unit;
import soot.jimple.infoflow.android.SetupApplication;
import soot.options.Options;
import soot.jimple.infoflow.android.InfoflowAndroidConfiguration;
import soot.util.HashMultiMap;
import soot.util.MultiMap;
import soot.jimple.infoflow.android.callbacks.AndroidCallbackDefinition;
import soot.toolkits.scalar.Pair;
import soot.jimple.toolkits.callgraph.VirtualCallSite;

import ca.ubc.ece.resess.slicer.dynamic.mandoline.instrumenter.AndroidInstrumenter;
import ca.ubc.ece.resess.slicer.dynamic.mandoline.graph.ICDG;



public class Slicer {
    public static final String SOOT_OUTPUT_STRING = System.getProperty("user.dir") + File.separator + "sootOutput/";
    MultiMap<SootClass, AndroidCallbackDefinition> callbackMethods = new HashMultiMap<>();
    Map<Pair<SootMethod, Unit>, String> threadCallers = new HashMap<>();
    Map<Pair<SootMethod, Unit>, SootClass> setterCallbackMap = new HashMap<>();
    Map <String, List<StatementInstance>> mapMethodInst = new LinkedHashMap<>();
    Map <String, StatementInstance> mapUnits = new LinkedHashMap<>();
    Map <String, StatementInstance> mapInvokations = new LinkedHashMap<>();
    Set<String> dynamicPrint = new LinkedHashSet<>();
    static Slicer instance;
    private String pathApk;
    private String platformPath;
    private String callbackFile;
    private String outDir;
    private String staticLogFile;
    private String loggerClassesJar;
    private String fileToParse;
    private String outFile;
    private int backwardSlicePos;
    private String variableString;
    private List<String> variables = new ArrayList<>();
    private SlicingWorkingSet workingSet;


    public Slicer(){}

    public static Slicer getInstance() {
        return instance;
    }

    public Set<String> getDynamicPrint() {
        return dynamicPrint;
    }

    public void setPathApk(String pathApk) {
        this.pathApk = pathApk;
    }


    public void setPlatformPath(String platformPath) {
        this.platformPath = platformPath;
    }

    public void setCallbackFile(String callbackFile) {
        this.callbackFile = callbackFile;
    }

    public void setOutDir(String outDir) {
        this.outDir = outDir;
        File outDirFile = new File(this.outDir);
        outDirFile.mkdirs();
        setStaticLogFile(this.outDir + File.separator + "static-log.log");
    }

    public void setStaticLogFile(String staticLogFile) {
        this.staticLogFile = staticLogFile;
    }

    public void setLoggerClassesJar(String loggerClassesJar) {
        this.loggerClassesJar = loggerClassesJar;
    }

    public void setFileToParse(String fileToParse) {
        this.fileToParse = fileToParse;
        this.outFile = fileToParse+"_icdg.log";
    }

    public void setBackwardSlicePos(int backwardSlicePos) {
        this.backwardSlicePos = backwardSlicePos;
    }

    public void setVariableString(String variableString) {
        this.variableString = variableString;
    }

    public void setVariables(List<String> variables) {
        this.variables = variables;
    }

    public String getStaticLogFile() {
        return staticLogFile;
    }

    public Map<Pair<SootMethod, Unit>, String> getThreadCallers() {
        return threadCallers;
    }

    public MultiMap<SootClass, AndroidCallbackDefinition> getCallbackMethods() {
        return callbackMethods;
    }

    public Map<Pair<SootMethod, Unit>, SootClass> getSetterCallbackMap() {
        return setterCallbackMap;
    }

    public void setDebug(boolean flag) {
        Constants.DEBUG = flag;
    }

    public void setWorkingSet(SlicingWorkingSet workingSet) {
        this.workingSet = workingSet;
    }

    public SlicingWorkingSet getWorkingSet() {
        return workingSet;
    }


    void printList(List <String> list, String outFile) {
        try {
            AnalysisLogger.log(Constants.DEBUG, "Printing to {}", outFile);
        FileUtils.writeLines(new File(outFile), list);
        } catch (IOException e) {
            AnalysisLogger.error("Unable to print file {}, {}", outFile, e);
        }
    }

    static void throwParseExceptionIfNull(Object object, String message){
        if (object == null) {
            throwParseException(message);
        }
    }

    public static void main(String [] args) {
            long startTime = System.nanoTime();
            boolean justTrace = false;
            Map<String, String> commands = CommandParser.parse(args);

            String mode = commands.get("m");
            if (mode == null || !(mode.equals("i") || mode.equals("g") || mode.equals("s"))) {
                throwParseException("Mode not provided / invalid mode");
            }
            Slicer slicer = new Slicer();

            String debug = commands.get("debug");
            if (debug != null) {
                slicer.setDebug(true);
            }

            slicer.setPathApk(commands.get("a"));
            throwParseExceptionIfNull(slicer.pathApk, "Apk path not provided");

            slicer.setPlatformPath(commands.get("p"));

            slicer.setCallbackFile(commands.get("c"));
 
            slicer.setOutDir(commands.get("o"));
            throwParseExceptionIfNull(slicer.outDir, "Output directory path not provided");

            if (commands.get("sl") != null) {
                slicer.setStaticLogFile(commands.get("sl"));
            }

            boolean instrumented = false;
            if(mode.equals("i")) {
                slicer.setLoggerClassesJar(commands.get("lc"));
                if (slicer.loggerClassesJar == null) {
                    throwParseExceptionIfNull(slicer.loggerClassesJar, "mandoline logger jar path not provided");
                }
                instrumented = slicer.instrument();
            }

            if (instrumented) {
                terminate(slicer.outDir, mode, startTime);
                return;
            }

            slicer.setFileToParse(commands.get("t"));
            throwParseExceptionIfNull(slicer.fileToParse, "Trace file path not provided");


            if(mode.equals("g")) {
                justTrace = true;
            } else {
                String sp = commands.get("sp");
                throwParseExceptionIfNull(sp, "No slicing criteria provided");
                String sd = commands.get("sd");
                throwParseExceptionIfNull(sd, "StubDroid path not provided");
                String tw = commands.get("tw");
                throwParseExceptionIfNull(tw, "Taint-wrapper path not provided");
            }
            List<TraceStatement> trs = Parser.readFile(slicer.fileToParse, slicer.staticLogFile);

            slicer.prepare();
            Slicer.instance = slicer;
            ICDG icdg = new ICDG(slicer.setterCallbackMap, slicer.callbackMethods, slicer.threadCallers);
            icdg.createDCFG(trs);

            slicer.printGraph(icdg);

            if(justTrace) {
                terminate(slicer.outDir, mode, startTime);
                return;
            }

            slicer.setBackwardSlicePos(Integer.parseInt(commands.get("sp")));
            slicer.setVariableString(commands.get("sv"));
            if (slicer.variableString == null) {
                slicer.variableString = "*";
            }
            String stubDroidPath = commands.get("sd");
            String taintWrapperPath = commands.get("tw");
            int forwardSlicePos = -1;
            if (commands.get("fw") != null) {
                forwardSlicePos = Integer.parseInt(commands.get("fw"));
            }

            boolean frameworkModel = true;
            boolean dataFlowsOnly = false;
            boolean controlFlowOnly = false;
            boolean sliceOnce = false;
            if (commands.containsKey("data")) {
                dataFlowsOnly = true;
            }
            if (commands.containsKey("ctrl")) {
                controlFlowOnly = true;
            }

            String frameworkPath = commands.get("f");

            
            FrameworkModel.setStubDroidPath(stubDroidPath);
            FrameworkModel.setTaintWrapperFile(taintWrapperPath);
            if (frameworkPath != null) {
                FrameworkModel.setExtraPath(frameworkPath);
            }

            StatementInstance stmt = icdg.mapNoUnits(slicer.backwardSlicePos);
            
            List<String> variables = new ArrayList<>();
            if (!slicer.variableString.equals("*")) {
                String[] split = slicer.variableString.split("-");
                for (int i = 0; i < split.length; i++) {
                    variables.add("$"+split[i]);
                }
            }
            slicer.setVariables(variables);

            Set<AccessPath> accessPaths = new HashSet<>();
            for (String v: slicer.variables) {
                accessPaths.add(new AccessPath(v, new Type(){
                    private static final long serialVersionUID = 1L;
                    @Override
                    public String toString() {
                        return "SlicingCriterionType";
                    }
                }, slicer.backwardSlicePos, AccessPath.NOT_DEFINED, stmt));
            }

            AnalysisLogger.log(Constants.DEBUG, "Slicing criterion: (" + slicer.backwardSlicePos + ", " + variables + ")");
            AnalysisLogger.log(Constants.DEBUG, "size of the trace after loading:"+icdg.getMapNumberUnits().keySet().size());
            AnalysisLogger.log(Constants.DEBUG, "Slicing from statement:"+ icdg.mapNoUnits(slicer.backwardSlicePos));


            slicer.setWorkingSet(new SlicingWorkingSet(false));
            DynamicSlice dynamicSlice = slicer.slice(icdg, frameworkModel, dataFlowsOnly, controlFlowOnly, sliceOnce, stmt, accessPaths, slicer.getWorkingSet());
            slicer.dynamicPrint = new LinkedHashSet<>();
            SlicePrinter.printSlices(dynamicSlice);
            SlicePrinter.printSliceGraph(dynamicSlice);
            SlicePrinter.printDotGraph(slicer.outDir, dynamicSlice);
            SlicePrinter.printSliceLines(slicer.outDir, dynamicSlice);
            SlicePrinter.printRawSlice(slicer.outDir, dynamicSlice);
            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss");
            LocalDateTime now = LocalDateTime.now();
            String resultFileName = slicer.outDir + File.separator + "result_" +mode+"_"+ dtf.format(now) + ".csv";
            SlicePrinter.printToCSV(resultFileName, dynamicSlice);

        terminate(slicer.outDir, mode, startTime);
    }
    public DynamicSlice slice(ICDG icdg,
            boolean frameworkModel, boolean dataFlowsOnly, boolean controlFlowOnly, boolean sliceOnce, StatementInstance start, Set<AccessPath> variables, SlicingWorkingSet workingSet) {
        return new SliceAndroid(icdg, frameworkModel, dataFlowsOnly, controlFlowOnly, sliceOnce, workingSet).slice(start, variables);
    }


    private void printGraph(ICDG icdg) {
        AnalysisLogger.log(Constants.DEBUG, "Printing graph...");
        List <String> listTOPrint = new ArrayList<>();
        Iterator<Entry<Integer, StatementInstance>> entries = icdg.getMapNumberUnits().entrySet().iterator();
        while (entries.hasNext()) {
            Entry<Integer, StatementInstance> thisEntry = entries.next();
            Integer lineNumber = thisEntry.getKey();
            StatementInstance statementInstance = thisEntry.getValue(); 
            listTOPrint.add(statementInstance.toString() 
                    + ":PRED:"+icdg.predecessorListOf(lineNumber) 
                    + ":SUCC:"+icdg.successorListOf(lineNumber) 
                    + ":TID:"+statementInstance.getThreadID());
        }
        printList(listTOPrint, outFile);
        AnalysisLogger.log(Constants.DEBUG, "Printing Complete.");
    }

    private boolean instrument() {
        boolean shouldTerminate = false;
        if (new File(SOOT_OUTPUT_STRING).isDirectory()) {
            deleteFolder(new File(SOOT_OUTPUT_STRING));
        }
        String instrumentOptions = "thread_time";
        String[] instrumenterArgs = new String[0];
        if (pathApk.endsWith(".apk")) {
            String[] instrumenterArgsTemp = {instrumentOptions, staticLogFile, "-w", "-allow-phantom-refs", "-process-multiple-dex", "-android-jars", platformPath, "-src-prec", "apk", "-output-format", "dex", "-process-dir", pathApk, "-process-dir", loggerClassesJar};
            instrumenterArgs = instrumenterArgsTemp;
        } else {
            throwParseException("Not apk file!");
        }
        
        throwParseExceptionIfNull(platformPath, "Platforms path not provided");
        throwParseExceptionIfNull(callbackFile, "Callback file path not provided");
        prepare();
        JimpleWriter jimpleWriter = new JimpleWriter(outDir);
        jimpleWriter.start(pathApk);
        Instrumenter instrumenter = new AndroidInstrumenter(callbackMethods, threadCallers);
        instrumenter.start(instrumenterArgs);

        shouldTerminate = true;
        return shouldTerminate;
    }


    private static void throwParseException(String message) {
        AnalysisLogger.log(Constants.DEBUG, "Invalid command line options: {}", message);
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp(CommandParser.CMD_LINE_SYNTAX, CommandParser.getOptions());
        throw new InvalidCommandsException();
    }
    
    private static void terminate(String outDir, String mode, long startTime){
        try {
            File folder = new File(SOOT_OUTPUT_STRING);
            File[] listOfFiles = folder.listFiles();
            Map<File, File> filesToMove = new HashMap<>();
            for (File file : listOfFiles) {
                if (file.isFile() && file.getName().contains(".apk")) {
                    filesToMove.put(file, new File(outDir + File.separator + file.getName().replace(".apk", "_" +mode + ".apk")));
                }
            }
            filesToMove.put(new File("apk-size.txt"), new File(outDir + File.separator + "apk-size.txt"));
            for (Map.Entry<File, File> entry: filesToMove.entrySet()) {
                if (entry.getKey().renameTo(entry.getValue())) {
                    // Ignore
                }
            }
            if (folder.isDirectory()) {
                deleteFolder(folder);
            }
        } catch (Exception e) {
            // Ignore
        }
        
        long endTime = System.nanoTime();
        double totalTime = (endTime-startTime)/1000000000.0;
        AnalysisLogger.log(Constants.DEBUG, "Time: {}", totalTime);
    }

    private static void deleteFolder(File folder) {
        try {
            FileUtils.forceDelete(folder);
        } catch (IOException e) {
            // pass
        }
    }

    public void prepare() {
        AnalysisCache.reset();
        if (pathApk.endsWith(".apk")) {
            prepareProcessingApk(pathApk, platformPath, callbackFile);
        } else {
            throwParseException("Not an apk file!");
        }
        
    }


    private void prepareProcessingApk(String apkPath, String platFormDir, String callbackFile) {
        AnalysisLogger.log(Constants.DEBUG, "Processing APK: {}", apkPath);
        InfoflowAndroidConfiguration config = new InfoflowAndroidConfiguration();
        config.getAnalysisFileConfig().setTargetAPKFile(apkPath);
        config.getAnalysisFileConfig().setAndroidPlatformDir(platFormDir);
        config.setMergeDexFiles(true);
        config.setExcludeSootLibraryClasses(false);
        config.getCallbackConfig().setCallbackAnalysisTimeout(60);
        SetupApplication app = new SetupApplication(config);
        app.setCallbackFile(callbackFile);
       
        try {
            app.constructCallgraph();
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        SootMethod entryPoint = app.getDummyMainMethod();
        Options.v().set_main_class(entryPoint.getSignature());
        Options.v().set_keep_line_number(true);
        app.printEntrypoints();
        this.callbackMethods = app.getCallbackMethods();
        this.setterCallbackMap = app.getCallbackAnalyzer().getSetterCallbackMap();
        ThreadCalls threadCalls = new ThreadCalls();
        threadCalls.process();
        Iterator<SootMethod> it =threadCalls.getMethodToReceivers().keyIterator();
        while(it.hasNext()) {
            SootMethod sm = it.next();
            for (Local l: threadCalls.getMethodToReceivers().get(sm)) {
                for (VirtualCallSite vc: threadCalls.getReceiverToSites().get(l)) {
                    threadCallers.put(new Pair<>(vc.getContainer(), vc.getStmt()), vc.subSig().getString());
                }
            }
        }
        AnalysisLogger.log(Constants.DEBUG, "Thread connections: {}", threadCallers);
        AnalysisLogger.log(Constants.DEBUG, "FlowDroid DummyMain: {}", entryPoint.getActiveBody());
    } 
}