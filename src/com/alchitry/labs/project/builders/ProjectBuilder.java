package com.alchitry.labs.project.builders;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.logging.Level;

import org.apache.commons.io.FileUtils;

import com.alchitry.labs.Locations;
import com.alchitry.labs.Util;
import com.alchitry.labs.gui.SignalSelectionDialog;
import com.alchitry.labs.gui.main.MainWindow;
import com.alchitry.labs.hardware.boards.Board;
import com.alchitry.labs.parsers.InstModule;
import com.alchitry.labs.parsers.Module;
import com.alchitry.labs.parsers.ProjectSignal;
import com.alchitry.labs.parsers.lucid.toVerilog.LucidToVerilog;
import com.alchitry.labs.parsers.tools.constraints.AlchitryConstraintsExtractor;
import com.alchitry.labs.parsers.tools.lucid.LucidDebugModifier;
import com.alchitry.labs.parsers.tools.verilog.VerilogLucidModuleFixer;
import com.alchitry.labs.parsers.types.ClockConstraint;
import com.alchitry.labs.parsers.types.PinConstraint;
import com.alchitry.labs.project.DebugInfo;
import com.alchitry.labs.project.Project;
import com.alchitry.labs.style.ParseException;

public abstract class ProjectBuilder {

	protected Project project;
	protected String workFolder;
	protected Process builder;
	protected ArrayList<ProjectSignal> debugSignals;
	protected int samples;
	protected List<File> debugSource;

	protected abstract void projectBuilder() throws Exception;

	public ProjectBuilder() {

	}

	public boolean isBuilding() {
		return builder != null && builder.isAlive();
	}

	public void stopBuild() {
		if (isBuilding()) {
			builder.destroyForcibly();
			try {
				builder.getOutputStream().close();
				builder.getInputStream().close();
				builder.getErrorStream().close();
			} catch (IOException e) {
				e.printStackTrace();
			}
			Util.println("");
			Util.println("Build aborted by user.", true);
		}
	}

	public void build(Project project, boolean debug) {
		BufferedWriter logWriter = null;
		try {
			MainWindow.mainWindow.setBuilding(true);
			this.project = project;
			workFolder = project.getFolder() + File.separatorChar + "work";

			Util.clearConsole();
			InstModule top = null;
			if (debug) {
				if (!Util.isGUI) {
					Util.showError("Debug builds only work in GUI mode!");
					return;
				}

				final InstModule ftop = project.getLucidSourceTree();
				top = ftop;
				boolean hasRegInt = false;
				boolean hasDebugRegInt = false;
				for (InstModule im : ftop.getChildren()) {
					if (im.getType().getName().equals("reg_interface"))
						hasRegInt = true;
					else if (im.getType().getName().equals("reg_interface_debug"))
						hasDebugRegInt = true;
				}
				if (hasDebugRegInt) {
					Util.showError("Your project can't contain the reg_interface_debug module!");
					return;
				}
				if (!hasRegInt) {
					Util.showError("Your project must contain the reg_interface module in mojo_top!");
					return;
				}
				Util.syncExec(new Runnable() {
					@Override
					public void run() {
						SignalSelectionDialog dialog = new SignalSelectionDialog(MainWindow.mainWindow.getShell());
						debugSignals = dialog.open(ftop);
						samples = dialog.getSamples();
					}
				});

				if (debugSignals == null)
					return;
			}

			File destDir = new File(workFolder);
			if (destDir.exists())
				FileUtils.deleteDirectory(destDir);
			if (!destDir.exists() || !destDir.isDirectory()) {
				boolean success = destDir.mkdir();
				if (!success) {
					Util.showError("Could not create project folder!");
					return;
				}
			}

			File logFile = new File(Util.assemblePath(workFolder, "build_output.log"));

			if (logFile.createNewFile()) {
				logWriter = new BufferedWriter(new FileWriter(logFile));
				Util.setConsoleLogger(logWriter);
			}

			if (project.checkForErrors()) {
				return;
			}

			if (debug) {
				File debugDir = new File(workFolder + File.separatorChar + "debug");
				if (!debugDir.exists() || !debugDir.isDirectory()) {
					boolean success = debugDir.mkdir();
					if (!success) {
						Util.showError("Could not create debug folder!");
						return;
					}
				}

				if ((debugSource = createDebugFiles(debugDir, top)) == null)
					return;
			} else {
				debugSignals = null;
				debugSource = null;
			}

			projectBuilder();

		} catch (Exception e) {
			Util.print(e);
			Util.log.log(Level.SEVERE, "Exception with project builder!", e);
		} finally {
			Util.setConsoleLogger(null);
			if (logWriter != null)
				try {
					logWriter.close();
				} catch (IOException e) {
					Util.println("Failed to close log file!", true);
				}
			MainWindow.mainWindow.setBuilding(false);
		}
	}

	private String getVerilogFile(String folder, String file, String srcFolder, List<Module> modules, InstModule im, List<InstModule> list) throws IOException {
		String vName;
		File src = new File(folder + File.separatorChar + file);
		if (im.getType().isNgc()) {
			vName = file;
			File dest = new File(srcFolder + File.separatorChar + file);

			if (!dest.exists() && !dest.createNewFile()) {
				Util.showError("Error building the project", "Impossible error? File exists but doesn't!");
				return null;
			}
			FileUtils.copyFile(new File(folder + File.separatorChar + file), dest);
		} else if (file.endsWith(".luc")) {
			vName = file.substring(0, file.length() - 4) + "_" + list.indexOf(im) + ".v";
			File dest = new File(srcFolder + File.separatorChar + vName);

			if (!dest.exists() && !dest.createNewFile()) {
				Util.showError("Error building the project", "Impossible error? File exists but doesn't!");
				return null;
			}

			String verilog = LucidToVerilog.convert(src.getAbsolutePath(), modules, im, list);
			FileUtils.writeStringToFile(dest, verilog);
		} else if (file.endsWith(".v")) {
			vName = file.substring(0, file.length() - 2) + "_" + list.indexOf(im) + ".v";
			File dest = new File(srcFolder + File.separatorChar + vName);

			String verilog = VerilogLucidModuleFixer.replaceModuleNames(im, src.getAbsolutePath(), modules, list);

			FileUtils.writeStringToFile(dest, verilog);

		} else {
			Util.showError("Error building the project", "Unknown file type!");
			return null;
		}

		return vName;
	}

	private void convertConstraintFile(String file, String folder, String srcFolder, List<String> constraintFiles) throws IOException {
		String fullPath = Util.assemblePath(folder, file);
		if (file.endsWith(".acf")) {
			AlchitryConstraintsExtractor extractor = new AlchitryConstraintsExtractor();
			extractor.parseAll(fullPath);
			Board board = project.getBoard();
			if (board.isType(Board.CU)) {
				if (!extractor.getPinConstraints().isEmpty()) {
					File pcf = new File(Util.assemblePath(srcFolder, file.substring(0, file.lastIndexOf('.')) + ".pcf"));
					if (!pcf.exists() && !pcf.createNewFile()) {
						Util.showError("Error building the project", "Impossible error? File exists but doesn't!");
						return;
					}
					StringBuilder sb = new StringBuilder();
					for (PinConstraint pc : extractor.getPinConstraints())
						sb.append(pc.getBoardConstraint(board));
					FileUtils.write(pcf, sb.toString());
					constraintFiles.add(pcf.getAbsolutePath());
				}

				if (!extractor.getClockConstraints().isEmpty()) {
					File sdc = new File(Util.assemblePath(srcFolder, file.substring(0, file.lastIndexOf('.')) + ".sdc"));
					if (!sdc.exists() && !sdc.createNewFile()) {
						Util.showError("Error building the project", "Impossible error? File exists but doesn't!");
						return;
					}
					StringBuilder sb = new StringBuilder();
					for (ClockConstraint cc : extractor.getClockConstraints())
						sb.append(cc.getBoardConstraint(board));
					FileUtils.write(sdc, sb.toString());
					constraintFiles.add(sdc.getAbsolutePath());
				}
			} else if (board.isType(Board.AU)) {
				if (!extractor.getPinConstraints().isEmpty() || !extractor.getClockConstraints().isEmpty()) {
					File xdc = new File(Util.assemblePath(srcFolder, file.substring(0, file.lastIndexOf('.')) + ".xdc"));
					if (!xdc.exists() && !xdc.createNewFile()) {
						Util.showError("Error building the project", "Impossible error? File exists but doesn't!");
						return;
					}
					StringBuilder sb = new StringBuilder();
					for (ClockConstraint cc : extractor.getClockConstraints())
						sb.append(cc.getBoardConstraint(board));
					for (PinConstraint pc : extractor.getPinConstraints())
						sb.append(pc.getBoardConstraint(board));
					FileUtils.write(xdc, sb.toString());
					constraintFiles.add(xdc.getAbsolutePath());
				}
			}
		} else {
			constraintFiles.add(fullPath);
		}
	}

	private ArrayList<String> mergeConstraintFiles(List<String> files) throws IOException {
		HashMap<String, File> mergedFiles = new HashMap<>();
		for (String cFile : files) {
			String ext = cFile.substring(cFile.lastIndexOf('.'), cFile.length());
			if (!mergedFiles.containsKey(ext)) {
				File f = new File(Util.assemblePath(workFolder, "constraint", "merged_constraint" + ext));
				mergedFiles.put(ext, f);
			}
			File f = mergedFiles.get(ext);
			FileUtils.write(f, FileUtils.readFileToString(new File(cFile)) + System.lineSeparator(), true);
		}

		ArrayList<String> mFiles = new ArrayList<>(mergedFiles.size());

		Iterator<Entry<String, File>> it = mergedFiles.entrySet().iterator();
		while (it.hasNext()) {
			Entry<String, File> pair = (Map.Entry<String, File>) it.next();
			mFiles.add(pair.getValue().getAbsolutePath());
		}

		return mFiles;
	}

	protected ArrayList<String> getConstraintFiles() throws IOException, ParseException {
		String srcFolder = workFolder + File.separatorChar + "constraint";
		ArrayList<String> constraintFiles = new ArrayList<String>();
		File destDir = new File(srcFolder);
		if (!destDir.exists() || !destDir.isDirectory()) {
			boolean success = destDir.mkdir();
			if (!success) {
				Util.showError("Error building the project", "Could not create source folder!");
				return null;
			}
		}

		// clean up old files
		for (File f : destDir.listFiles()) {
			f.delete();
		}

		String folder = project.getConstraintFolder();

		for (String cf : project.getConstraintFiles(false)) {
			convertConstraintFile(cf, folder, srcFolder, constraintFiles);
		}
		folder = Locations.COMPONENTS;
		for (String cf : project.getConstraintFiles(true)) {
			convertConstraintFile(cf, folder, srcFolder, constraintFiles);
		}

		if (project.getBoard().isType(Board.CU))
			constraintFiles = mergeConstraintFiles(constraintFiles);

		return constraintFiles;
	}

	protected ArrayList<String> getVerilogFiles() throws IOException, ParseException {
		String srcFolder = workFolder + File.separatorChar + "verilog";
		ArrayList<String> verilogFiles = new ArrayList<String>();
		File destDir = new File(srcFolder);
		if (!destDir.exists() || !destDir.isDirectory()) {
			boolean success = destDir.mkdir();
			if (!success) {
				Util.showError("Error building the project", "Could not create source folder!");
				return null;
			}
		}

		// clean up old files
		for (File f : destDir.listFiles()) {
			f.delete();
		}

		project.updateGlobals();

		List<Module> modules = project.getModules(debugSource);
		Module topModule = null;
		for (Module m : modules) {
			String fileName = m.getFileName();
			if (fileName != null && fileName.endsWith("_0_debug.luc")) {
				topModule = m;
				break;
			}
		}
		List<InstModule> list = project.getModuleList(modules, true, topModule);

		for (InstModule im : list) {
			if (im.isPrimitive())
				continue;
			String folder = im.getType().getFolder();
			String file = im.getType().getFileName();
			String vFile = getVerilogFile(folder, file, srcFolder, modules, im, list);
			if (vFile != null) {
				verilogFiles.add(vFile);
				if (im.getType().isNgc()) {
					vFile = getVerilogFile(folder, file.substring(0, vFile.lastIndexOf(".")) + ".ngc", srcFolder, modules, im, list);
					verilogFiles.add(vFile);
				}
			}
		}

		// if (!addVerilogFiles(verilogFiles, project.getSourceFolder(), project.getSourceFiles(), srcFolder, modules))
		// return null;
		// if (!addVerilogFiles(verilogFiles, Locations.COMPONENTS, project.getComponentFiles(), srcFolder, modules))
		// return null;

		return verilogFiles;
	}

	public static class DebugFile {
		public File file;
		public String projectPath;
		public int index;
		public InstModule instModule;

		public DebugFile(InstModule im, File f, String ppath, int idx) {
			file = f;
			projectPath = ppath;
			index = idx;
			instModule = im;
		}

		@Override
		public int hashCode() {
			if (file != null && projectPath != null)
				return file.hashCode() ^ projectPath.hashCode();
			if (file != null)
				return file.hashCode();
			if (projectPath != null)
				return projectPath.hashCode();
			return 0;
		}

		@Override
		public boolean equals(Object o) {
			if (o instanceof DebugFile) {
				DebugFile df = (DebugFile) o;
				return Objects.equals(df.file, file) && Objects.equals(df.projectPath, projectPath);
			}
			return false;
		}
	}

	protected List<File> createDebugFiles(File debugDir, InstModule topModule) {
		HashSet<DebugFile> debugFiles = new HashSet<>();
		List<File> debugSource = new ArrayList<>();
		int index = 0;
		for (ProjectSignal sig : debugSignals) {
			StringBuilder sb = new StringBuilder();
			boolean first = true;
			for (InstModule im : sig.getPath()) {
				if (!first)
					sb.append(".");
				else
					first = false;
				sb.append(im.getName());
				if (debugFiles.add(new DebugFile(im, new File(im.getType().getFolder() + File.separator + im.getType().getFileName()), sb.toString(), index)))
					index++;
			}
		}
		long nonce = (long) (Math.random() * 0xffffffffL);
		for (DebugFile f : debugFiles) {
			String origName = f.file.getName();
			String newName = origName.substring(0, origName.length() - 4) + "_" + f.index + "_debug.luc"; // add index to name
			File destFile = new File(debugDir.getPath() + File.separator + newName);
			debugSource.add(destFile);

			String modifiedFile = LucidDebugModifier.modifyForDebug(f.file.getPath(), debugSignals, f.instModule, f.instModule == topModule, debugFiles, nonce, samples);
			try {
				FileUtils.write(destFile, modifiedFile);
			} catch (IOException e) {
				Util.showError("Failed to copy files for debugging!");
				Util.log.log(Level.SEVERE, "Failed to copy files for debugging", e);
				return null;
			}
		}

		project.setDebugInfo(new DebugInfo(debugSignals, nonce));

		return debugSource;
	}
}
