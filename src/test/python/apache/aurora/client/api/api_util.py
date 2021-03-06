from apache.aurora.client.api.scheduler_client import SchedulerProxy

from gen.apache.aurora.api import ReadOnlyScheduler


class SchedulerThriftApiSpec(ReadOnlyScheduler.Iface):
  """
  A concrete definition of the thrift API used by the client. Intended primarily to be used as a
  spec definition for unit testing, since the client effectively augments function signatures by
  allowing callers to omit the session argument (in SchedulerProxy). These signatures should be
  identical to those in AuroraAdmin.Iface, with the session removed.
  """

  def setQuota(self, ownerRole, quota):
    pass

  def forceTaskState(self, taskId, status):
    pass

  def performBackup(self):
    pass

  def listBackups(self):
    pass

  def stageRecovery(self, backupId):
    pass

  def queryRecovery(self, query):
    pass

  def deleteRecoveryTasks(self, query):
    pass

  def commitRecovery(self):
    pass

  def unloadRecovery(self):
    pass

  def startMaintenance(self, hosts):
    pass

  def drainHosts(self, hosts):
    pass

  def maintenanceStatus(self, hosts):
    pass

  def endMaintenance(self, hosts):
    pass

  def snapshot(self):
    pass

  def rewriteConfigs(self, request):
    pass

  def createJob(self, description, lock):
    pass

  def scheduleCronJob(self, description, lock):
    pass

  def descheduleCronJob(self, job, lock):
    pass

  def startCronJob(self, job):
    pass

  def restartShards(self, job, shardIds, lock):
    pass

  def killTasks(self, query, lock):
    pass

  def addInstances(self, config, lock):
    pass

  def acquireLock(self, lockKey):
    pass

  def releaseLock(self, lock, validation):
    pass

  def replaceCronTemplate(self, config, lock):
    pass

  def startJobUpdate(self, request):
    pass

  def pauseJobUpdate(self, jobKey):
    pass

  def resumeJobUpdate(self, jobKey):
    pass

  def abortJobUpdate(self, jobKey):
    pass

  def pulseJobUpdate(self, updateId):
    pass


class SchedulerProxyApiSpec(SchedulerThriftApiSpec, SchedulerProxy):
  """
  A concrete definition of the API provided by SchedulerProxy.
  """

  def url(self):
    pass
