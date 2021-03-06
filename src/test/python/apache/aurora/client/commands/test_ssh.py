#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

import contextlib

from mock import create_autospec, Mock, patch

from apache.aurora.client.commands.ssh import ssh

from .util import AuroraClientCommandTest

from gen.apache.aurora.api.constants import LIVE_STATES
from gen.apache.aurora.api.ttypes import (
    AssignedTask,
    Identity,
    JobKey,
    ResponseCode,
    ScheduleStatus,
    ScheduleStatusResult,
    TaskConfig,
    TaskEvent,
    TaskQuery
)


class TestSshCommand(AuroraClientCommandTest):

  @classmethod
  def setup_mock_options(cls):
    """set up to get a mock options object."""
    mock_options = Mock()
    mock_options.tunnels = []
    mock_options.executor_sandbox = False
    mock_options.ssh_user = None
    mock_options.disable_all_hooks = False
    return mock_options

  @classmethod
  def create_mock_scheduled_tasks(cls):
    jobs = []
    for name in ['foo', 'bar', 'baz']:
      job_key = JobKey(role=cls.TEST_ROLE, environment=cls.TEST_ENV, name=name)
      job = Mock()
      job.key = job_key
      job.failure_count = 0
      job.assignedTask = create_autospec(spec=AssignedTask, instance=True)
      job.assignedTask.taskId = 1287391823
      job.assignedTask.slaveHost = 'slavehost'
      job.assignedTask.task = create_autospec(spec=TaskConfig, instance=True)
      job.assignedTask.task.executorConfig = Mock()
      job.assignedTask.task.maxTaskFailures = 1
      job.assignedTask.task.metadata = []
      job.assignedTask.task.job = job_key
      job.assignedTask.task.owner = Identity(role=cls.TEST_ROLE)
      job.assignedTask.task.environment = cls.TEST_ENV
      job.assignedTask.task.jobName = name
      job.assignedTask.task.numCpus = 2
      job.assignedTask.task.ramMb = 2
      job.assignedTask.task.diskMb = 2
      job.assignedTask.instanceId = 4237894
      job.assignedTask.assignedPorts = {}
      job.status = ScheduleStatus.RUNNING
      mockEvent = create_autospec(spec=TaskEvent, instance=True)
      mockEvent.timestamp = 28234726395
      mockEvent.status = ScheduleStatus.RUNNING
      mockEvent.message = "Hi there"
      job.taskEvents = [mockEvent]
      jobs.append(job)
    return jobs

  @classmethod
  def create_status_response(cls):
    resp = cls.create_simple_success_response()
    resp.result.scheduleStatusResult = ScheduleStatusResult()
    resp.result.scheduleStatusResult.tasks = cls.create_mock_scheduled_tasks()
    return resp

  @classmethod
  def create_nojob_status_response(cls):
    resp = cls.create_simple_success_response()
    resp.result.scheduleStatusResult = ScheduleStatusResult()
    resp.result.scheduleStatusResult.tasks = []
    return resp

  @classmethod
  def create_failed_status_response(cls):
    return cls.create_blank_response(ResponseCode.INVALID_REQUEST, 'No tasks found for query')

  def test_successful_ssh(self):
    """Test the ssh command."""
    mock_options = self.setup_mock_options()
    (mock_api, mock_scheduler_proxy) = self.create_mock_api()
    mock_scheduler_proxy.getTasksStatus.return_value = self.create_status_response()
    sandbox_args = {'slave_root': '/slaveroot', 'slave_run_directory': 'slaverun'}
    with contextlib.nested(
        patch('apache.aurora.client.api.SchedulerProxy', return_value=mock_scheduler_proxy),
        patch('apache.aurora.client.factory.CLUSTERS', new=self.TEST_CLUSTERS),
        patch('twitter.common.app.get_options', return_value=mock_options),
        patch('apache.aurora.client.api.command_runner.DistributedCommandRunner.sandbox_args',
            return_value=sandbox_args),
        patch('subprocess.call', return_value=0)) as (
            mock_scheduler_proxy_class,
            mock_clusters,
            options,
            mock_runner_args_patch,
            mock_subprocess):
      ssh(['west/mchucarroll/test/hello', '1', 'ls'], mock_options)

      # The status command sends a getTasksStatus query to the scheduler,
      # and then prints the result.
      mock_scheduler_proxy.getTasksStatus.assert_called_with(
          TaskQuery(jobKeys=[JobKey(role='mchucarroll', environment='test', name='hello')],
                    instanceIds=set([1]),
                    statuses=LIVE_STATES))
      mock_subprocess.assert_called_with(['ssh', '-t', 'mchucarroll@slavehost',
          'cd /slaveroot/slaves/*/frameworks/*/executors/thermos-1287391823/runs/'
          'slaverun/sandbox;ls'])

  def test_ssh_deprecation_message(self):
    """Test the ssh command."""
    mock_options = self.setup_mock_options()
    (mock_api, mock_scheduler_proxy) = self.create_mock_api()
    mock_scheduler_proxy.getTasksStatus.return_value = self.create_status_response()
    sandbox_args = {'slave_root': '/slaveroot', 'slave_run_directory': 'slaverun'}
    with contextlib.nested(
        patch('apache.aurora.client.api.SchedulerProxy', return_value=mock_scheduler_proxy),
        patch('apache.aurora.client.factory.CLUSTERS', new=self.TEST_CLUSTERS),
        patch('twitter.common.app.get_options', return_value=mock_options),
        patch('apache.aurora.client.api.command_runner.DistributedCommandRunner.sandbox_args',
            return_value=sandbox_args),
        patch('subprocess.call', return_value=0),
        patch('apache.aurora.client.commands.ssh.v1_deprecation_warning')) as (
            mock_scheduler_proxy_class,
            mock_clusters,
            options,
            mock_runner_args_patch,
            mock_subprocess,
            mock_dep_warn):
      mock_options.tunnels = ['100:hundred', '1000:thousand']
      try:
        ssh(['west/mchucarroll/test/hello', '1', 'ls'], mock_options)
      except SystemExit:
        # It's going to fail with an error about unknown ports, but we don't
        # care: we just want to verify that it generated a deprecation warning
        # with the correct --tunnels parameters.
        pass
      mock_dep_warn.assert_called_with('ssh',
          ['task', 'ssh', 'west/mchucarroll/test/hello/1',
           '--tunnels=100:hundred', '--tunnels=1000:thousand',
           '--command="ls"'])

  def test_ssh_job_not_found(self):
    """Test the ssh command when the query returns no tasks."""
    mock_options = self.setup_mock_options()
    (mock_api, mock_scheduler_proxy) = self.create_mock_api()
    mock_scheduler_proxy.getTasksStatus.return_value = self.create_nojob_status_response()
    with contextlib.nested(
        patch('apache.aurora.client.api.SchedulerProxy', return_value=mock_scheduler_proxy),
        patch('apache.aurora.client.factory.CLUSTERS', new=self.TEST_CLUSTERS),
        patch('twitter.common.app.get_options', return_value=mock_options),
        patch('subprocess.call', return_value=0)) as (
            mock_scheduler_proxy_class,
            mock_clusters,
            options,
            mock_subprocess):
      self.assertRaises(SystemExit, ssh, ['west/mchucarroll/test/hello', '1', 'ls'], mock_options)

      assert mock_subprocess.call_count == 0
