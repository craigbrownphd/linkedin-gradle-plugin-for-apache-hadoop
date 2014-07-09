package com.linkedin.gradle.hadoop;

import org.gradle.api.GradleException
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.FileTree
import org.gradle.api.tasks.Exec;
import org.gradle.api.tasks.Sync;

class PigPlugin implements Plugin<Project> {
  PigExtension pigExtension;
  Project project;

  void apply(Project project) {
    this.pigExtension = new PigExtension(project);
    this.project = project;

    project.extensions.add("pig", pigExtension);
    readPigProperties();

    if (pigExtension.generateTasks) {
      addBuildCacheTask();
      addPigScriptTasks();
      addShowPigJobsTask();
      addExecPigJobsTask();
    }
  }

  // Reads the properties file containing information to configure the plugin.
  void readPigProperties() {
    File file = new File("${project.projectDir}/.pigProperties");
    if (file.exists()) {
      Properties properties = new Properties();
      file.withInputStream { inputStream ->
        properties.load(inputStream);
      }
      // Now read the properties into the extension and validate them.
      pigExtension.readFromProperties(properties);
      pigExtension.validateProperties();
    }
  }

  // Adds a task to set up the cache directory that will be rsync'd to the host that will run Pig.
  void addBuildCacheTask() {
    project.tasks.create(name: "buildPigCache", type: Sync) {
      description = "Build the cache directory to run Pig scripts by Gradle tasks";
      group = "Hadoop Plugin";

      FileTree pigFileTree = project.fileTree([
        dir: "${project.projectDir}",
        include: "src/**/*.pig"
      ]);

      from pigFileTree;
      from project.configurations[pigExtension.dependencyConf];
      into "${pigExtension.pigCacheDir}/${project.name}";
      includeEmptyDirs = false;
    }
  }

  // For each Pig script, adds a task to run the script on a host. With these tasks, you cannot
  // pass parameters to the script.
  void addPigScriptTasks() {
    Set<String> taskNames = new HashSet<String>();

    FileTree pigFileTree = project.fileTree([
      dir: "${project.projectDir}",
      include: "src/**/*.pig"
    ]);

    pigFileTree.each { File file ->
      String fileName = file.getName();
      String filePath = file.getAbsolutePath();
      String taskName = PigTaskHelper.buildUniqueTaskName(fileName, taskNames);
      taskNames.add(taskName);
      addPigScriptTask(filePath, taskName, null);
    }
  }

  // Adds a task to run the given Pig script on a host. With this task, you cannot pass parameters
  // to the script.
  void addPigScriptTask(String filePath, String taskName, Map<String, String> parameters) {
    String relaPath = filePath.replace("${project.projectDir}/", "");
    String projectDir = "${pigExtension.pigCacheDir}/${project.name}";

    project.tasks.create(name: "run_${taskName}", type: Exec) {
      commandLine "sh", "${projectDir}/run_${taskName}.sh"
      dependsOn project.tasks["buildPigCache"]
      description = "Run the Pig script ${relaPath}";
      group = "Hadoop Plugin";

      doFirst {
        writePigExecScript(filePath, taskName, parameters);
      }
    }
  }

  // Adds tasks to display the Pig jobs specified by the user in the Azkaban DSL and a task that
  // can execute these jobs.
  void addShowPigJobsTask() {
    project.tasks.create("showPigJobs") {
      description = "Lists Pig jobs configured in the Azkaban DSL that can be run with the runPigJob task";
      group = "Hadoop Plugin";

      doLast {
        Map<String, PigJob> pigJobs = PigTaskHelper.findConfiguredPigJobs(project);
        logger.lifecycle("The following Pig jobs configured in the AzkabanDSL can be run with gradle runPigJob -Pjob=<job name>");

        pigJobs.each { String jobName, PigJob job ->
          String pigScript = job.script ?: ""
          logger.lifecycle("${jobName} : ${pigScript}");
        }
      }
    }
  }

  // Adds a task to run a Pig job configured in the Azkaban DSL. This enables the user to pass
  // parameters to the script.
  void addExecPigJobsTask() {
    project.tasks.create(name: "runPigJob", type: Exec) {
      dependsOn project.tasks["buildPigCache"]
      description = "Runs a Pig job configured in the Azkaban DSL with gradle runPigJob -Pjob=<job name>";
      group = "Hadoop Plugin";

      doFirst {
        if (!project.job) {
          throw new GradleException("You must use -Pjob=<job name> to specify the job name with runPigJob");
        }

        Map<String, PigJob> pigJobs = PigTaskHelper.findConfiguredPigJobs(project);
        PigJob pigJob = pigJobs.get(project.job);

        if (pigJob == null) {
          throw new GradleException("Could not find Pig job with name ${project.jobName}");
        }

        if (pigJob.script == null) {
          throw new GradleException("Pig job with name ${project.jobName} does not have a script set");
        }

        File file = new File(pigJob.script);
        if (!file.exists()) {
          throw new GradleException("Script ${pigJob.script} for Pig job with name ${project.jobName} does not exist");
        }

        String filePath = file.getAbsolutePath();
        writePigExecScript(filePath, project.job, pigJob.parameters);

        String projectDir = "${pigExtension.pigCacheDir}/${project.name}";
        commandLine "sh", "${projectDir}/run_${project.job}.sh"
      }
    }
  }

  // Writes out the shell script that will run Pig for the given script and parameters.
  void writePigExecScript(String filePath, String taskName, Map<String, String> parameters) {
    String relaPath = filePath.replace("${project.projectDir}/", "");
    String projectDir = "${project.extensions.pig.pigCacheDir}/${project.name}";

    String pigCommand = project.extensions.pig.pigCommand;
    String pigOptions = project.extensions.pig.pigOptions ?: "";
    String pigParams  = PigTaskHelper.buildPigParameters(parameters);

    if (project.extensions.pig.remoteHostName) {
      String remoteHostName = project.extensions.pig.remoteHostName;
      String remoteShellCmd = project.extensions.pig.remoteShellCmd;
      String remoteCacheDir = project.extensions.pig.remoteCacheDir;
      String remoteProjDir = "${remoteCacheDir}/${project.name}";

      new File("${projectDir}/run_${taskName}.sh").withWriter { out ->
        out.writeLine("#!/bin/sh");
        out.writeLine("echo ====================");
        out.writeLine("echo Running the script ${projectDir}/run_${taskName}.sh");
        out.writeLine("echo Creating directory ${remoteCacheDir} on host ${remoteHostName}");
        out.writeLine("${remoteShellCmd} ${remoteHostName} mkdir -p ${remoteCacheDir}");
        out.writeLine("echo Syncing local directory ${projectDir} to ${remoteHostName}:${remoteCacheDir}");
        out.writeLine("rsync -av ${projectDir} -e \"${remoteShellCmd}\" ${remoteHostName}:${remoteCacheDir}");
        out.writeLine("echo Executing ${pigCommand} on host ${remoteHostName}");
        out.writeLine("${remoteShellCmd} ${remoteHostName} ${pigCommand} -Dpig.additional.jars=${remoteProjDir}/*.jar ${pigOptions} -f ${remoteProjDir}/${relaPath} ${pigParams}");
      }
    }
    else {
      new File("${projectDir}/run_${taskName}.sh").withWriter { out ->
        out.writeLine("#!/bin/sh");
        out.writeLine("echo ====================");
        out.writeLine("echo Running the script ${projectDir}/run_${taskName}.sh");
        out.writeLine("echo Executing ${pigCommand} on the local host");
        out.writeLine("${pigCommand} -Dpig.additional.jars=${projectDir}/*.jar ${pigOptions} -f ${projectDir}/${relaPath} ${pigParams}");
      }
    }
  }
}