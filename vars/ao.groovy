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
 * Finds the upstream projects for the given job.
 * Returns an array of upstream projects, possibly empty but never null
 */
def getUpstreamProjects(jenkins, upstreamProjectsCache, workflowJob) {
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
def getAllUpstreamProjects(jenkins, upstreamProjectsCache, allUpstreamProjectsCache, workflowJob) {
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
def pruneUpstreamProjects(jenkins, upstreamProjectsCache, currentWorkflowJob, upstreamProjects) {
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
def getDepth(jenkins, upstreamProjectsCache, depthMap, workflowJob, jobUpstreamProjects) {
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
