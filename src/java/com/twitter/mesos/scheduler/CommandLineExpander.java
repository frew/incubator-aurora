package com.twitter.mesos.scheduler;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import org.apache.commons.lang.StringUtils;

import com.twitter.mesos.gen.AssignedTask;

/**
 * Utility class to handle command line expansion.
 *
 * @author William Farner
 */
public final class CommandLineExpander {

  private static final Logger LOG = Logger.getLogger(CommandLineExpander.class.getName());

  private CommandLineExpander() {
    // Utility.
  }

  // Double percents to escape formatting sequence.
  private static final String PORT_FORMAT = "%%port:%s%%";
  private static final Pattern PORT_REQUEST_PATTERN =
      Pattern.compile(String.format(PORT_FORMAT, "(\\w+)"));
  private static final String SHARD_ID_REGEXP = "%shard_id%";
  private static final String TASK_ID_REGEXP = "%task_id%";

  /**
   * Gets the number of ports requested in a command line.
   *
   * @param commandLine Command line to search for port requests.
   * @return Number of ports requested.
   */
  public static int getNumPortsRequested(String commandLine) {
    return getPortNames(commandLine).size();
  }

  /**
   * Expands the command line in a task, applying the provided allocated ports.
   *
   * @param immutableTask The task containing a command line that should be expanded.
   * @param allocatedPorts Ports allocated for the task.  There must be sufficient ports available
   *     based on the expansion requests in the task's command line, else
   *     {@link IllegalArgumentException} is thrown.
   * @return A copy of {@code immutableTask}, with the command line fully expanded, and the
   *     allocated ports map populated appropriately.
   */
  public static AssignedTask expand(AssignedTask immutableTask, Set<Integer> allocatedPorts) {
    AssignedTask task = new AssignedTask(immutableTask);
    String commandLine = task.getTask().getStartCommand();

    // Expand shard ID.
    commandLine = commandLine.replaceAll(SHARD_ID_REGEXP,
        String.valueOf(task.getTask().getShardId()));

    // Expand task ID.
    commandLine = commandLine.replaceAll(TASK_ID_REGEXP, task.getTaskId());

    // Expand ports.
    Map<String, Integer> ports = Maps.newHashMap();
    Set<Integer> portsRemaining = Sets.newHashSet(allocatedPorts);
    Iterator<Integer> portConsumer = Iterables.consumingIterable(portsRemaining).iterator();

    Set<String> portNames = getPortNames(commandLine);
    for (String portName : portNames) {
      Preconditions.checkArgument(portConsumer.hasNext(),
          "Allocated ports %s were not sufficient to expand %s", allocatedPorts, immutableTask);
      int portNumber = portConsumer.next();
      ports.put(portName, portNumber);

      commandLine = commandLine.replaceAll(
          String.format(PORT_FORMAT, portName),
          String.valueOf(portNumber));
    }

    if (!portsRemaining.isEmpty()) {
      LOG.warning(String.format(""));
    }

    task.getTask().setStartCommand(commandLine);
    task.setAssignedPorts(ports);
    return task;
  }

  private static Set<String> getPortNames(String commandLine) {
    if (StringUtils.isBlank(commandLine)) {
      return ImmutableSet.of();
    }

    ImmutableSet.Builder<String> ports = ImmutableSet.builder();
    Matcher m = PORT_REQUEST_PATTERN.matcher(commandLine);
    while (m.find()) {
      ports.add(m.group(1));
    }
    return ports.build();
  }
}
