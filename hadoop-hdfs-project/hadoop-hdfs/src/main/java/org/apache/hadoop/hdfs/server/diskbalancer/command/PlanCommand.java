/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.hadoop.hdfs.server.diskbalancer.command;

import com.google.common.base.Preconditions;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.tools.DiskBalancer;
import org.apache.hadoop.hdfs.server.diskbalancer.datamodel
    .DiskBalancerDataNode;
import org.apache.hadoop.hdfs.server.diskbalancer.planner.NodePlan;
import org.apache.hadoop.hdfs.server.diskbalancer.planner.Step;
import org.codehaus.jackson.map.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Class that implements Plan Command.
 * <p>
 * Plan command reads the Cluster Info and creates a plan for specified data
 * node or a set of Data nodes.
 * <p>
 * It writes the output to a default location unless changed by the user.
 */
public class PlanCommand extends Command {
  private double thresholdPercentage;
  private int bandwidth;
  private int maxError;

  /**
   * Constructs a plan command.
   */
  public PlanCommand(Configuration conf) {
    super(conf);
    this.thresholdPercentage = 1;
    this.bandwidth = 0;
    this.maxError = 0;
    addValidCommandParameters(DiskBalancer.NAMENODEURI, "Name Node URI or " +
        "file URI for cluster");

    addValidCommandParameters(DiskBalancer.OUTFILE, "Output file");
    addValidCommandParameters(DiskBalancer.BANDWIDTH, "Maximum Bandwidth to " +
        "be used while copying.");
    addValidCommandParameters(DiskBalancer.THRESHOLD, "Percentage skew that " +
        "we tolerate before diskbalancer starts working.");
    addValidCommandParameters(DiskBalancer.MAXERROR, "Max errors to tolerate " +
        "between 2 disks");
    addValidCommandParameters(DiskBalancer.VERBOSE, "Run plan command in " +
        "verbose mode.");
  }

  /**
   * Runs the plan command. This command can be run with various options like
   * <p>
   * -plan -node IP -plan -node hostName -plan -node DatanodeUUID
   *
   * @param cmd - CommandLine
   */
  @Override
  public void execute(CommandLine cmd) throws Exception {
    LOG.debug("Processing Plan Command.");
    Preconditions.checkState(cmd.hasOption(DiskBalancer.PLAN));
    verifyCommandOptions(DiskBalancer.PLAN, cmd);

    if (cmd.getOptionValue(DiskBalancer.PLAN) == null) {
      throw new IllegalArgumentException("A node name is required to create a" +
          " plan.");
    }

    if (cmd.hasOption(DiskBalancer.BANDWIDTH)) {
      this.bandwidth = Integer.parseInt(cmd.getOptionValue(DiskBalancer
          .BANDWIDTH));
    }

    if (cmd.hasOption(DiskBalancer.MAXERROR)) {
      this.maxError = Integer.parseInt(cmd.getOptionValue(DiskBalancer
          .MAXERROR));
    }

    readClusterInfo(cmd);
    String output = null;
    if (cmd.hasOption(DiskBalancer.OUTFILE)) {
      output = cmd.getOptionValue(DiskBalancer.OUTFILE);
    }
    setOutputPath(output);

    // -plan nodename is the command line argument.
    DiskBalancerDataNode node = getNode(cmd.getOptionValue(DiskBalancer.PLAN));
    if (node == null) {
      throw new IllegalArgumentException("Unable to find the specified node. " +
          cmd.getOptionValue(DiskBalancer.PLAN));
    }
    this.thresholdPercentage = getThresholdPercentage(cmd);
    setNodesToProcess(node);

    List<NodePlan> plans = getCluster().computePlan(this.thresholdPercentage);
    setPlanParams(plans);

    LOG.info("Writing plan to : {}", getOutputPath());
    System.out.printf("Writing plan to : %s%n", getOutputPath());

    try (FSDataOutputStream beforeStream = create(String.format(
        DiskBalancer.BEFORE_TEMPLATE,
        cmd.getOptionValue(DiskBalancer.PLAN)))) {
      beforeStream.write(getCluster().toJson()
          .getBytes(StandardCharsets.UTF_8));
    }

    try (FSDataOutputStream planStream = create(String.format(
        DiskBalancer.PLAN_TEMPLATE,
        cmd.getOptionValue(DiskBalancer.PLAN)))) {
      planStream.write(getPlan(plans).getBytes(StandardCharsets.UTF_8));
    }

    if (cmd.hasOption(DiskBalancer.VERBOSE)) {
      printToScreen(plans);
    }
  }

  /**
   * Gets extended help for this command.
   *
   * @return Help Message
   */
  @Override
  protected String getHelp() {
    return "This commands creates a disk balancer plan for given datanode";
  }

  /**
   * Get Threshold for planning purpose.
   *
   * @param cmd - Command Line Argument.
   * @return double
   */
  private double getThresholdPercentage(CommandLine cmd) {
    Double value = 0.0;
    if (cmd.hasOption(DiskBalancer.THRESHOLD)) {
      value = Double.parseDouble(cmd.getOptionValue(DiskBalancer.THRESHOLD));
    }

    if ((value <= 0.0) || (value > 100.0)) {
      value = getConf().getDouble(
          DFSConfigKeys.DFS_DISK_BALANCER_MAX_DISK_THRUPUT,
          DFSConfigKeys.DFS_DISK_BALANCER_MAX_DISK_THRUPUT_DEFAULT);
    }
    return value;
  }

  /**
   * Prints a quick summary of the plan to screen.
   *
   * @param plans - List of NodePlans.
   */
  static private void printToScreen(List<NodePlan> plans) {
    System.out.println("\nPlan :\n");
    System.out.println(StringUtils.repeat("=", 80));
    System.out.println("Source Disk\t\t Dest.Disk\t\t Move Size\t Type\n ");
    for (NodePlan plan : plans) {
      for (Step step : plan.getVolumeSetPlans()) {
        System.out.println(String.format("%s\t%s\t%s\t%s",
            step.getSourceVolume().getPath(),
            step.getDestinationVolume().getPath(),
            step.getSizeString(step.getBytesToMove()),
            step.getDestinationVolume().getStorageType()));
      }
    }

    System.out.println(StringUtils.repeat("=", 80));
  }

  /**
   * Sets user specified plan parameters.
   *
   * @param plans - list of plans.
   */
  private void setPlanParams(List<NodePlan> plans) {
    for (NodePlan plan : plans) {
      for (Step step : plan.getVolumeSetPlans()) {
        if (this.bandwidth > 0) {
          step.setBandwidth(this.bandwidth);
        }
        if (this.maxError > 0) {
          step.setMaxDiskErrors(this.maxError);
        }
      }
    }
  }

  /**
   * Returns a Json represenation of the plans.
   *
   * @param plan - List of plans.
   * @return String.
   * @throws IOException
   */
  private String getPlan(List<NodePlan> plan) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    return mapper.writeValueAsString(plan);
  }
}
