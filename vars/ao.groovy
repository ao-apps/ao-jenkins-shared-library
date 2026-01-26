#!/usr/bin/env groovy
/*
 * ao-jenkins-shared-library - Jenkins shared library for all AO-supported Jenkins Pipelines.
 * Copyright (C) 2026  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of ao-jenkins-shared-library.
 *
 * ao-jenkins-shared-library is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ao-jenkins-shared-library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with ao-jenkins-shared-library.  If not, see <https://www.gnu.org/licenses/>.
 */

/*
 * JDK versions
 */
def defJdkVersions(binding) {
  if (!binding.hasVariable('deployJdk')) {
    // Matches build.yml:java-version
    binding.setVariable('deployJdk', '21')
  }
  if (!binding.hasVariable('buildJdks')) {
    binding.setVariable(
      'buildJdks',
      ['11', '17', '21'] // Changes must be copied to matrix axes!
    )
  }
  if (!binding.hasVariable('testJdks')) {
    binding.setVariable(
      'testJdks',
      ['11', '17', '21'] // Changes must be copied to matrix axes!
    )
  }
}

def defUpstreamProjects(binding) {
  if (!binding.hasVariable('upstreamProjects')) {
    binding.setVariable('upstreamProjects', [])
  }
}

def defProjectDir(binding, currentBuild) {
  if (!binding.hasVariable('projectDir')) {
    def scriptPath = currentBuild.rawBuild.parent.definition.scriptPath
    def defaultProjectDir
    if (scriptPath == 'Jenkinsfile') {
      defaultProjectDir = '.'
    } else if (scriptPath == 'book/Jenkinsfile') {
      defaultProjectDir = 'book'
    } else if (scriptPath == 'devel/Jenkinsfile') {
      defaultProjectDir = 'devel'
    } else {
      throw new Exception("Unexpected value for 'scriptPath': '$scriptPath'")
    }
    binding.setVariable('projectDir', defaultProjectDir)
  }
}

def defDisableSubmodules(binding) {
  if (!binding.hasVariable('disableSubmodules')) {
    binding.setVariable('disableSubmodules', true)
  }
}

def defSparseCheckoutPaths(binding) {
  if (!binding.hasVariable('sparseCheckoutPaths')) {
    def projectDir = binding.getVariable('projectDir')
    def defaultSparseCheckoutPaths
    if (projectDir == '.') {
      defaultSparseCheckoutPaths = [
        [path:'/*'],
        [path:'!/book/'],
        [path:'/book/pom.xml'],
        [path:'!/devel/']
      ]
    } else if (projectDir == 'book' || projectDir == 'devel') {
      defaultSparseCheckoutPaths = [
        [path:'/.gitignore'],
        [path:'/.gitmodules'],
        [path:"/$projectDir/"]
      ]
    } else {
      throw new Exception("Unexpected value for 'projectDir': '$projectDir'")
    }
    binding.setVariable('sparseCheckoutPaths', defaultSparseCheckoutPaths)
  }
}

def defScmUrl(binding, scm) {
  if (!binding.hasVariable('scmUrl')) {
    // Automatically determine Git URL: https://stackoverflow.com/a/38255364
    if (scm.userRemoteConfigs.size() == 1) {
      binding.setVariable('scmUrl', scm.userRemoteConfigs[0].url)
    } else {
      throw new Exception("Precisely one SCM remote expected: '" + scm.userRemoteConfigs + "'")
    }
  }
}

def defScmBranch(binding, scm) {
  if (!binding.hasVariable('scmBranch')) {
    // Automatically determine branch
    if (scm.branches.size() == 1) {
      def scmBranchPrefix = 'refs/heads/'
      def defaultScmBranch = scm.branches[0].name
      if (defaultScmBranch.startsWith(scmBranchPrefix)) {
        defaultScmBranch = defaultScmBranch.substring(scmBranchPrefix.length())
        binding.setVariable('scmBranch', defaultScmBranch)
      } else {
        throw new Exception("SCM branch does not start with '$scmBranchPrefix': '$defaultScmBranch'")
      }
    } else {
      throw new Exception("Precisely one SCM branch expected: '" + scm.branches + "'")
    }
  }
}

def defScmBrowser(binding) {
  if (!binding.hasVariable('scmBrowser')) {
    def scmUrl = binding.getVariable('scmUrl')
    // Automatically determine SCM browser
    def aoappsPrefix        = '/srv/git/ao-apps/'
    def newmediaworksPrefix = '/srv/git/nmwoss/'
    def defaultScmBrowser
    if (scmUrl.startsWith(aoappsPrefix)) {
      // Is also mirrored to GitHub user "ao-apps"
      def repo = scmUrl.substring(aoappsPrefix.length())
      if (repo.endsWith('.git')) {
        repo = repo.substring(0, repo.length() - 4)
      }
      defaultScmBrowser = [$class: 'GithubWeb',
        repoUrl: 'https://github.com/ao-apps/' + repo
      ]
    } else if (scmUrl.startsWith(newmediaworksPrefix)) {
      // Is also mirrored to GitHub user "newmediaworks"
      def repo = scmUrl.substring(newmediaworksPrefix.length())
      if (repo.endsWith('.git')) {
        repo = repo.substring(0, repo.length() - 4)
      }
      defaultScmBrowser = [$class: 'GithubWeb',
        repoUrl: 'https://github.com/newmediaworks/' + repo
      ]
    } else if (scmUrl.startsWith('/srv/git/') || scmUrl.startsWith('ssh://')) {
      // No default
      defaultScmBrowser = null
    } else {
      throw new Exception("Unexpected SCM URL: '$scmUrl'")
    }
    binding.setVariable('scmBrowser', defaultScmBrowser)
  }
}

def defBuildPriorityAndPrunedUpstreamProjects(binding) {
  // Variables temporarily used in project resolution
  def tempUpstreamProjectsCache = [:]
  def tempJenkins = Jenkins.get()
  // Find the current project
  def tempCurrentWorkflowJob = currentBuild.rawBuild.parent
  if (!(tempCurrentWorkflowJob instanceof org.jenkinsci.plugins.workflow.job.WorkflowJob)) {
    throw new Exception("tempCurrentWorkflowJob is not a WorkflowJob: $tempCurrentWorkflowJob")
  }

  // Prune set of upstreamProjects
  def prunedUpstreamProjects = pruneUpstreamProjects(tempJenkins, tempUpstreamProjectsCache, tempCurrentWorkflowJob, binding.getVariable('upstreamProjects'))

  if (!binding.hasVariable('buildPriority')) {
    // Find the longest path through all upstream projects, which will be used as both job priority and
    // nice value.  This will ensure proper build order in all cases.  However, it may prevent some
    // possible concurrency since reduction to simple job priority number loses information about which
    // are critical paths on the upstream project graph.
    def defaultBuildPriority = getDepth(tempJenkins, tempUpstreamProjectsCache, [:], tempCurrentWorkflowJob, prunedUpstreamProjects)
    if (defaultBuildPriority > 30) throw new Exception("defaultBuildPriority > 30, increase global configuration: $defaultBuildPriority")
    binding.setVariable('buildPriority', defaultBuildPriority)
  }
  def buildPriority = binding.getVariable('buildPriority');
  if (buildPriority < 1 || buildPriority > 30) {
    throw new Exception("buildPriority out of range 1 - 30: $buildPriority")
  }
}

/*
 * Finds the upstream projects for the given job.
 * Returns an array of upstream projects, possibly empty but never null
 */
private def getUpstreamProjects(jenkins, upstreamProjectsCache, workflowJob) {
  def fullName = workflowJob.fullName
  def upstreamProjects = upstreamProjectsCache[fullName]
  if (upstreamProjects == null) {
    upstreamProjects = []
    workflowJob.triggers?.each {triggerDescriptor, trigger ->
      if (trigger instanceof jenkins.triggers.ReverseBuildTrigger) {
        trigger.upstreamProjects.split(',').each {upstreamProject ->
          upstreamProject = upstreamProject.trim()
          if (upstreamProject != '') {
            upstreamProjects << upstreamProject
          }
        }
      } else {
        throw new Exception("$fullName: trigger is not a ReverseBuildTrigger: $upstreamFullName -> $trigger")
      }
    }
    upstreamProjectsCache[fullName] = upstreamProjects
  }
  return upstreamProjects
}

/*
 * Gets the set of full names for the given workflowJob and all transitive upstream projects.
 */
private def getAllUpstreamProjects(jenkins, upstreamProjectsCache, allUpstreamProjectsCache, workflowJob) {
  def fullName = workflowJob.fullName
  def allUpstreamProjects = allUpstreamProjectsCache[fullName]
  if (allUpstreamProjects == null) {
    if (allUpstreamProjectsCache.containsKey(fullName)) {
      throw new Exception("$fullName: Loop detected in upstream project graph")
    }
    // Add to map with null for loop detection
    allUpstreamProjectsCache[fullName] = null
    // Create new set
    allUpstreamProjects = [] as Set<String>
    // Always contains self
    allUpstreamProjects << fullName
    // Transitively add all
    for (upstreamProject in getUpstreamProjects(jenkins, upstreamProjectsCache, workflowJob)) {
      def upstreamWorkflowJob = jenkins.getItem(upstreamProject, workflowJob)
      if (upstreamWorkflowJob == null) {
        throw new Exception("$fullName: upstreamWorkflowJob not found: '$upstreamProject'")
      }
      if (!(upstreamWorkflowJob instanceof org.jenkinsci.plugins.workflow.job.WorkflowJob)) {
        throw new Exception("$fullName: $upstreamProject: upstreamWorkflowJob is not a WorkflowJob: $upstreamWorkflowJob")
      }
      allUpstreamProjects.addAll(getAllUpstreamProjects(jenkins, upstreamProjectsCache, allUpstreamProjectsCache, upstreamWorkflowJob))
    }
    // Cache result
    allUpstreamProjectsCache[fullName] = allUpstreamProjects
  }
  return allUpstreamProjects
}

/*
 * Prunes upstream projects in two ways:
 *
 * 1) Remove duplicates by job fullName (handles different relative paths to same project)
 *
 * 2) Removes direct upstream projects that are also transitive through others
 */
private def pruneUpstreamProjects(jenkins, upstreamProjectsCache, currentWorkflowJob, upstreamProjects) {
  // Quick unique by name, and ensures a new object to not affect parameter
  upstreamProjects = upstreamProjects.unique()
  // Find the set of all projects for each upstreamProject
  def upstreamFullNames = []
  def allUpstreamProjects = []
  def allUpstreamProjectsCache = [:]
  for (upstreamProject in upstreamProjects) {
    def upstreamWorkflowJob = jenkins.getItem(upstreamProject, currentWorkflowJob)
    if (upstreamWorkflowJob == null) {
      throw new Exception("${currentWorkflowJob.fullName}: upstreamWorkflowJob not found: '$upstreamProject'")
    }
    if (!(upstreamWorkflowJob instanceof org.jenkinsci.plugins.workflow.job.WorkflowJob)) {
      throw new Exception("${currentWorkflowJob.fullName}: $upstreamProject: upstreamWorkflowJob is not a WorkflowJob: $upstreamWorkflowJob")
    }
    upstreamFullNames << upstreamWorkflowJob.fullName
    allUpstreamProjects << getAllUpstreamProjects(jenkins, upstreamProjectsCache, allUpstreamProjectsCache, upstreamWorkflowJob)
  }
  allUpstreamProjectsCache = null
  // Prune upstream, quadratic algorithm
  def pruned = []
  def len = upstreamProjects.size()
  assert len == upstreamFullNames.size()
  assert len == allUpstreamProjects.size()
  for (int i = 0; i < len; i++) {
    def upstreamFullName = upstreamFullNames[i]
    // Make sure no contained by any other upstream
    def transitiveOnOther = false
    for (int j = 0; j < len; j++) {
      if (j != i && allUpstreamProjects[j].contains(upstreamFullName)) {
        //echo "pruneUpstreamProjects: ${currentWorkflowJob.fullName}: Pruned due to transitive: $upstreamFullName found in ${upstreamProjects[j]}"
        transitiveOnOther = true
        break
      }
    }
    if (!transitiveOnOther) {
      //echo "pruneUpstreamProjects: ${currentWorkflowJob.fullName}: Direct: ${upstreamProjects[i]}"
      pruned << upstreamProjects[i]
    }
  }
  return pruned
}

/*
 * Finds the depth of the given job in the upstream project graph
 * Top-most projects will be 1, which matches the job priorities for Priority Sorter plugin
 *
 * Uses depthMap for tracking
 *
 * Mapping from project full name to depth, where final depth is >= 1
 * During traversal, a project is first added to the map with a value of 0, which is used to detect graph loops
 */
private def getDepth(jenkins, upstreamProjectsCache, depthMap, workflowJob, jobUpstreamProjects) {
  def fullName = workflowJob.fullName
  def depth = depthMap[fullName]
  if (depth == null) {
    // Add to map with value 0 for loop detection
    depthMap[fullName] = 0
    def maxUpstream = 0
    jobUpstreamProjects.each {upstreamProject ->
      def upstreamWorkflowJob = jenkins.getItem(upstreamProject, workflowJob)
      if (upstreamWorkflowJob == null) {
        throw new Exception("$fullName: upstreamWorkflowJob not found: '$upstreamProject'")
      }
      if (!(upstreamWorkflowJob instanceof org.jenkinsci.plugins.workflow.job.WorkflowJob)) {
        throw new Exception("$fullName: $upstreamProject: upstreamWorkflowJob is not a WorkflowJob: $upstreamWorkflowJob")
      }
      def upstreamProjects = getUpstreamProjects(jenkins, upstreamProjectsCache, upstreamWorkflowJob)
      def upstreamDepth = getDepth(jenkins, upstreamProjectsCache, depthMap, upstreamWorkflowJob, upstreamProjects)
      if (upstreamDepth > maxUpstream) maxUpstream = upstreamDepth
    }
    depth = maxUpstream + 1
    //echo "$fullName: $depth"
    depthMap[fullName] = depth
  } else if (depth == 0) {
    throw new Exception("$fullName: Loop detected in upstream project graph")
  }
  return depth;
}

/*
 * Determines whether to continue the current build.  The build will continue when the
 * currentBuild.result is null, SUCCESS, or UNSTABLE.
 */
def continueCurrentBuild() {
  return currentBuild.result == null ||
         currentBuild.result == hudson.model.Result.SUCCESS ||
         currentBuild.result == hudson.model.Result.UNSTABLE
}

//
// Scripts pulled out of pipeline due to "General error during class generation: Method too large"
//

// Make sure working tree not modified after checkout
private def checkTreeUnmodifiedScriptCheckout(niceCmd) {
  return """#!/bin/bash
s="\$(${niceCmd}git status --short)"
if [ "\$s" != "" ]
then
  echo "Working tree modified after checkout:"
  echo "\$s"
  exit 1
fi
"""
}

// Make sure working tree not modified by build or test
private def checkTreeUnmodifiedScriptBuild(niceCmd) {
  return """#!/bin/bash
s="\$(${niceCmd}git status --short)"
if [ "\$s" != "" ]
then
  echo "Working tree modified during build or test:"
  echo "\$s"
  exit 1
fi
"""
}

// Temporarily move surefire-reports before withMaven to avoid duplicate logging of test results
private def moveSurefireReportsScript() {
  return """#!/bin/bash
if [ -d target/surefire-reports ]
then
  mv target/surefire-reports target/surefire-reports.do-not-report-twice
fi
"""
}

// Restore surefire-reports
private def restoreSurefireReportsScript() {
  return """#!/bin/bash
if [ -d target/surefire-reports.do-not-report-twice ]
then
  mv target/surefire-reports.do-not-report-twice target/surefire-reports
fi
"""
}

// Make sure working tree not modified by deploy
private def checkTreeUnmodifiedScriptDeploy(niceCmd) {
  return """#!/bin/bash
s="\$(${niceCmd}git status --short)"
if [ "\$s" != "" ]
then
  echo "Working tree modified during deploy:"
  echo "\$s"
  exit 1
fi
"""
}

/*
 * See https://plugins.jenkins.io/build-history-manager/
 */
def setupBuildDiscarder() {
  buildDiscarder(BuildHistoryManager([
    [
      // Keep most recent not_built build, which is useful to know which
      // builds have been superseded during their quiet period.
      conditions: [BuildResult(
        matchNotBuilt: true
      )],
      matchAtMost: 1,
      continueAfterMatch: false
    ],
    [
      // Keep most recent aborted build, which is useful to know what the build is waiting for
      // and to see that the build is still pending in Active and Blinkenlichten views.
      conditions: [BuildResult(
        matchAborted: true
      )],
      matchAtMost: 1,
      continueAfterMatch: false
    ],
    [
      // Keep most recent 50 success/unstable/failure builds
      conditions: [BuildResult(
        // All statuses except ABORTED from
        // https://github.com/jenkinsci/build-history-manager-plugin/blob/master/src/main/java/pl/damianszczepanik/jenkins/buildhistorymanager/model/conditions/BuildResultCondition.java
        matchSuccess: true,
        matchUnstable: true,
        matchFailure: true
      )],
      matchAtMost: 50,
      continueAfterMatch: false
    ],
    [
      actions: [DeleteBuild()]
    ]
  ]))
}

def checkReadySteps() {
  try {
    timeout(time: 15, unit: 'MINUTES') {
      try {
        // See https://javadoc.jenkins.io/jenkins/model/Jenkins.html
        // See https://javadoc.jenkins.io/hudson/model/Job.html
        // See https://javadoc.jenkins.io/hudson/model/Run.html
        // See https://javadoc.jenkins.io/hudson/model/Result.html
        def jenkins = Jenkins.get();
        // Get the mapping of all active dependencies and their current status
        def upstreamProjectsCache = [:]
        def allUpstreamProjectsCache = [:]
        // Find the current project
        def currentWorkflowJob = currentBuild.rawBuild.parent
        if (!(currentWorkflowJob instanceof org.jenkinsci.plugins.workflow.job.WorkflowJob)) {
          throw new Exception("currentWorkflowJob is not a WorkflowJob: ${currentWorkflowJob.fullName}")
        }
        // Get all upstream projects (and the current)
        def allUpstreamProjects = getAllUpstreamProjects(
          jenkins,
          upstreamProjectsCache,
          allUpstreamProjectsCache,
          currentWorkflowJob
        )
        // Remove current project
        if (!allUpstreamProjects.removeElement(currentWorkflowJob.fullName)) {
          throw new Exception("currentWorkflowJob is not in allUpstreamProjects: ${currentWorkflowJob.fullName}")
        }
        // Check queue and get statuses, stop searching on first found unready
        allUpstreamProjects.each {upstreamProject ->
          def upstreamWorkflowJob = jenkins.getItemByFullName(upstreamProject)
          if (upstreamWorkflowJob == null) {
            throw new Exception("${currentWorkflowJob.fullName}: upstreamWorkflowJob not found: '$upstreamProject'")
          }
          if (!(upstreamWorkflowJob instanceof org.jenkinsci.plugins.workflow.job.WorkflowJob)) {
            throw new Exception("${currentWorkflowJob.fullName}: $upstreamProject: upstreamWorkflowJob is not a WorkflowJob: $upstreamWorkflowJob")
          }
          def lastBuild = upstreamWorkflowJob.getLastBuild();
          if (lastBuild == null) {
            throw new IllegalStateException("${currentWorkflowJob.fullName}: Aborting due to dependency never built: ${upstreamWorkflowJob.fullName}")
          }
          if (lastBuild.isBuilding()) {
            throw new IllegalStateException("${currentWorkflowJob.fullName}: Aborting due to dependency currently building: ${upstreamWorkflowJob.fullName} #${lastBuild.number}")
          }
          def result = lastBuild.result;
          if (result != hudson.model.Result.SUCCESS) {
            throw new IllegalStateException("${currentWorkflowJob.fullName}: Aborting due to dependency last build not successful: ${upstreamWorkflowJob.fullName} #${lastBuild.number} is $result")
          }
        }
      } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
        // rethrow timeout
        throw e;
      } catch (IllegalStateException e) {
        // It is assumed the only cause of IllegalStateException is our own throws
        catchError(message: 'Aborted due to dependencies not ready', buildResult: 'ABORTED', stageResult: 'ABORTED') {
          error(e.message)
        }
      }
    }
  } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
    if (e.isActualInterruption()) {
      echo 'Rethrowing actual interruption instead of converting timeout to failure'
      throw e;
    }
    if (currentBuild.result == null || currentBuild.result == hudson.model.Result.ABORTED) {
      error((e.message == null) ? 'Converting timeout to failure' : "Converting timeout to failure: ${e.message}")
    }
  }
}

/*
 * Actual git checkout used by both attempts.
 */
private def gitCheckout(scmUrl, scmBranch, scmBrowser, sparseCheckoutPaths, disableSubmodules) {
  checkout scm: [$class: 'GitSCM',
    userRemoteConfigs: [[
      url: scmUrl,
      refspec: "+refs/heads/$scmBranch:refs/remotes/origin/$scmBranch"
    ]],
    branches: [[name: "refs/heads/$scmBranch"]],
    browser: scmBrowser,
    extensions: [
      // CleanCheckout was too aggressive and removed the workspace .m2 folder, added "sh" steps below
      // [$class: 'CleanCheckout'],
      [$class: 'CloneOption',
        // See https://issues.jenkins.io/browse/JENKINS-45586
        shallow: false,
        // depth: 20,
        honorRefspec: true
      ],
      [$class: 'SparseCheckoutPaths',
        sparseCheckoutPaths: sparseCheckoutPaths
      ],
      [$class: 'SubmoduleOption',
        disableSubmodules: disableSubmodules,
        shallow: false
        // depth: 20
      ]
    ]
  ]
}

/*
 * Git version 2.34.1 is failing when fetching without submodules, which is our most common usage.
 * It fails only on the first fetch, then succeeds on subsequent fetches.
 * This issue is expected to be resolved in 2.35.1.
 *
 * To workaround this issue, we are allowing to retry the Git fetch by catching the first failure.
 *
 * See https://github.com/git/git/commit/c977ff440735e2ddc2ef18b18ae9a653bb8650fe
 * See https://gitlab.com/gitlab-org/gitlab/-/issues/27287
 *
 * TODO: Remove once on Git >= 2.35.1
 */
def workaroundGit27287Steps(scmUrl, scmBranch, scmBrowser, sparseCheckoutPaths, disableSubmodules) {
  try {
    timeout(time: 15, unit: 'MINUTES') {
      // See https://www.jenkins.io/doc/pipeline/steps/params/gitscm/
      // See https://www.jenkins.io/doc/pipeline/steps/workflow-scm-step/#checkout-check-out-from-version-control
      // See https://stackoverflow.com/questions/43293334/sparsecheckout-in-jenkinsfile-pipeline
      catchError(message: 'Git 2.34.1 first fetch fails when not fetching submodules, will retry', buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
        gitCheckout(scmUrl, scmBranch, scmBrowser, sparseCheckoutPaths, disableSubmodules)
      }
    }
  } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
    if (e.isActualInterruption()) {
      echo 'Rethrowing actual interruption instead of converting timeout to failure'
      throw e;
    }
    if (currentBuild.result == null || currentBuild.result == hudson.model.Result.ABORTED) {
      error((e.message == null) ? 'Converting timeout to failure' : "Converting timeout to failure: ${e.message}")
    }
  }
}

def checkoutScmSteps(projectDir, niceCmd, scmUrl, scmBranch, scmBrowser, sparseCheckoutPaths, disableSubmodules) {
  try {
    timeout(time: 15, unit: 'MINUTES') {
      gitCheckout(scmUrl, scmBranch, scmBrowser, sparseCheckoutPaths, disableSubmodules)
      sh "${niceCmd}git verify-commit HEAD"
      sh "${niceCmd}git reset --hard"
      // git clean -fdx was iterating all of /.m2 despite being ignored
      sh "${niceCmd}git clean -fx -e ${(projectDir == '.') ? '/.m2' : ('/' + projectDir + '/.m2')}"
      // Make sure working tree not modified after checkout
      sh checkTreeUnmodifiedScriptCheckout(niceCmd)
    }
  } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
    if (e.isActualInterruption()) {
      echo 'Rethrowing actual interruption instead of converting timeout to failure'
      throw e;
    }
    if (currentBuild.result == null || currentBuild.result == hudson.model.Result.ABORTED) {
      error((e.message == null) ? 'Converting timeout to failure' : "Converting timeout to failure: ${e.message}")
    }
  }
}

def buildSteps(projectDir, niceCmd, maven, deployJdk, mavenOpts, mvnCommon, jdk, buildPhases, testWhenExpression, testJdks) {
  try {
    timeout(time: 1, unit: 'HOURS') {
      dir(projectDir) {
        withMaven(
          maven: maven,
          mavenOpts: mavenOpts,
          mavenLocalRepo: ".m2/repository-jdk-$jdk",
          jdk: "jdk-$jdk"
        ) {
          sh "${niceCmd}$MVN_CMD $mvnCommon ${jdk == deployJdk ? '' : "-Dalt.build.dir=target-jdk-$jdk -Pjenkins-build-altjdk "}$buildPhases"
        }
      }
      script {
        // Create a separate copy for full test matrix
        if (testWhenExpression.call()) {
          testJdks.each() {testJdk ->
            if (testJdk != jdk) {
              sh "${niceCmd}rm $projectDir/target-jdk-$jdk-$testJdk -rf"
              sh "${niceCmd}cp -al $projectDir/target${jdk == deployJdk ? '' : "-jdk-$jdk"} $projectDir/target-jdk-$jdk-$testJdk"
            }
          }
        }
      }
    }
  } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
    if (e.isActualInterruption()) {
      echo 'Rethrowing actual interruption instead of converting timeout to failure'
      throw e;
    }
    if (currentBuild.result == null || currentBuild.result == hudson.model.Result.ABORTED) {
      error((e.message == null) ? 'Converting timeout to failure' : "Converting timeout to failure: ${e.message}")
    }
  }
}

def testSteps(projectDir, niceCmd, deployJdk, maven, mavenOpts, mvnCommon, jdk, testJdk) {
  try {
    timeout(time: 1, unit: 'HOURS') {
      def buildDir  = "target${(testJdk == jdk) ? (jdk == deployJdk ? '' : "-jdk-$jdk") : ("-jdk-$jdk-$testJdk")}"
      def coverage  = "${(jdk == deployJdk && testJdk == deployJdk && fileExists(projectDir + '/src/main/java') && fileExists(projectDir + '/src/test')) ? '-Pcoverage' : '-P!coverage'}"
      def testGoals = "${(coverage == '-Pcoverage') ? 'jacoco:prepare-agent surefire:test jacoco:report' : 'surefire:test'}"
      dir(projectDir) {
        withMaven(
          maven: maven,
          mavenOpts: mavenOpts,
          mavenLocalRepo: ".m2/repository-jdk-$jdk",
          jdk: "jdk-$testJdk"
        ) {
          sh "${niceCmd}$MVN_CMD $mvnCommon -Dalt.build.dir=$buildDir $coverage $testGoals"
        }
      }
    }
  } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
    if (e.isActualInterruption()) {
      echo 'Rethrowing actual interruption instead of converting timeout to failure'
      throw e;
    }
    if (currentBuild.result == null || currentBuild.result == hudson.model.Result.ABORTED) {
      error((e.message == null) ? 'Converting timeout to failure' : "Converting timeout to failure: ${e.message}")
    }
  }
}

def deploySteps(projectDir, niceCmd, deployJdk, maven, mavenOpts, mvnCommon) {
  try {
    timeout(time: 1, unit: 'HOURS') {
      // Make sure working tree not modified by build or test
      sh checkTreeUnmodifiedScriptBuild(niceCmd)
      dir(projectDir) {
        // Download artifacts from last successful build of this job
        // See https://plugins.jenkins.io/copyartifact/
        // See https://www.jenkins.io/doc/pipeline/steps/copyartifact/#copyartifacts-copy-artifacts-from-another-project
        copyArtifacts(
          projectName: "/${JOB_NAME}",
          selector: lastSuccessful(stable: true),
          // *.pom included so pom-only projects have something to successfully download
          // The other extensions match the types processed by ao-ant-tasks
          filter: '**/*.pom, **/*.aar, **/*.jar, **/*.war, **/*.zip',
          target: 'target/last-successful-artifacts',
          flatten: true,
          optional: (params.requireLastBuild == null) ? true : !params.requireLastBuild
        )
        // Temporarily move surefire-reports before withMaven to avoid duplicate logging of test results
        sh moveSurefireReportsScript()
        withMaven(
          maven: maven,
          mavenOpts: mavenOpts,
          mavenLocalRepo: ".m2/repository-jdk-$deployJdk",
          jdk: "jdk-$deployJdk"
        ) {
          sh "${niceCmd}$MVN_CMD $mvnCommon -Pnexus,jenkins-deploy,publish deploy"
        }
        // Restore surefire-reports
        sh restoreSurefireReportsScript()
      }
      // Make sure working tree not modified by deploy
      sh checkTreeUnmodifiedScriptDeploy(niceCmd)
    }
  } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
    if (e.isActualInterruption()) {
      echo 'Rethrowing actual interruption instead of converting timeout to failure'
      throw e;
    }
    if (currentBuild.result == null || currentBuild.result == hudson.model.Result.ABORTED) {
      error((e.message == null) ? 'Converting timeout to failure' : "Converting timeout to failure: ${e.message}")
    }
  }
}

def sonarQubeAnalysisSteps(projectDir, niceCmd, deployJdk, maven, mavenOpts, mvnCommon) {
  try {
    timeout(time: 15, unit: 'MINUTES') {
      // Not doing shallow: sh "${niceCmd}git fetch --unshallow || true" // SonarQube does not currently support shallow fetch
      dir(projectDir) {
        withSonarQubeEnv(installationName: 'AO SonarQube') {
          withMaven(
            maven: maven,
            mavenOpts: mavenOpts,
            mavenLocalRepo: ".m2/repository-jdk-$deployJdk",
            jdk: "jdk-$deployJdk"
          ) {
            sh "${niceCmd}$MVN_CMD $mvnCommon -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml sonar:sonar"
          }
        }
      }
    }
  } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
    if (e.isActualInterruption()) {
      echo 'Rethrowing actual interruption instead of converting timeout to failure'
      throw e;
    }
    if (currentBuild.result == null || currentBuild.result == hudson.model.Result.ABORTED) {
      error((e.message == null) ? 'Converting timeout to failure' : "Converting timeout to failure: ${e.message}")
    }
  }
}

def qualityGateSteps() {
  try {
    timeout(time: 1, unit: 'HOURS') {
      waitForQualityGate(webhookSecretId: 'SONAR_WEBHOOK', abortPipeline: false)
    }
  } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
    if (e.isActualInterruption()) {
      echo 'Rethrowing actual interruption instead of converting timeout to failure'
      throw e;
    }
    if (currentBuild.result == null || currentBuild.result == hudson.model.Result.ABORTED) {
      error((e.message == null) ? 'Converting timeout to failure' : "Converting timeout to failure: ${e.message}")
    }
  }
}

def analysisSteps() {
  try {
    timeout(time: 15, unit: 'MINUTES') {
      def tools = []
      tools << checkStyle(pattern: 'target/checkstyle-result.xml', skipSymbolicLinks: true)
      tools << java()
      tools << javaDoc()
      // Detect JUnit results from presence of surefire-reports directory
      if (fileExists('target/surefire-reports')) {
        tools << junitParser(pattern: 'target*/surefire-reports/TEST-*.xml', skipSymbolicLinks: true)
      }
      tools << mavenConsole()
      // php()
      // sonarQube(), // TODO: sonar-report.json not found
      tools << spotBugs(pattern: 'target/spotbugsXml.xml', skipSymbolicLinks: true)
      // taskScanner()
      recordIssues(
        aggregatingResults: true,
        skipPublishingChecks: true,
        sourceCodeEncoding: 'UTF-8',
        tools: tools
      )
    }
  } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
    if (e.isActualInterruption()) {
      echo 'Rethrowing actual interruption instead of converting timeout to failure'
      throw e;
    }
    if (currentBuild.result == null || currentBuild.result == hudson.model.Result.ABORTED) {
      error((e.message == null) ? 'Converting timeout to failure' : "Converting timeout to failure: ${e.message}")
    }
  }
}

def postFailure(failureEmailTo) {
  emailext(
    to: failureEmailTo,
    subject: "[Jenkins] ${currentBuild.fullDisplayName} build failed",
    body: "${env.BUILD_URL}console"
  )
}
