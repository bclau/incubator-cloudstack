// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.storage.download;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.concurrent.ConcurrentHashMap;

import javax.ejb.Local;
import javax.inject.Inject;

import org.apache.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.Listener;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;

import com.cloud.agent.api.storage.DeleteTemplateCommand;
import com.cloud.agent.api.storage.DeleteVolumeCommand;
import com.cloud.agent.api.storage.DownloadCommand;

import com.cloud.agent.api.storage.DownloadCommand.Proxy;
import com.cloud.agent.api.storage.DownloadCommand.ResourceType;
import com.cloud.agent.api.storage.DownloadProgressCommand.RequestType;

import com.cloud.agent.api.storage.DownloadProgressCommand;
import com.cloud.agent.api.storage.ListTemplateAnswer;
import com.cloud.agent.api.storage.ListTemplateCommand;
import com.cloud.agent.api.storage.ListVolumeAnswer;
import com.cloud.agent.api.storage.ListVolumeCommand;

import com.cloud.agent.manager.Commands;
import com.cloud.alert.AlertManager;
import com.cloud.configuration.Config;
import com.cloud.configuration.dao.ConfigurationDao;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.event.EventTypes;
import com.cloud.event.UsageEventUtils;
import com.cloud.event.dao.UsageEventDao;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.StorageUnavailableException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.resource.ResourceManager;
import com.cloud.storage.Storage.ImageFormat;

import com.cloud.storage.StorageManager;
import com.cloud.storage.SwiftVO;
import com.cloud.storage.VMTemplateHostVO;
import com.cloud.storage.VMTemplateStorageResourceAssoc;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.VMTemplateStorageResourceAssoc.Status;
import com.cloud.storage.VMTemplateZoneVO;
import com.cloud.storage.VolumeHostVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.storage.dao.SwiftDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VMTemplateHostDao;
import com.cloud.storage.dao.VMTemplatePoolDao;
import com.cloud.storage.dao.VMTemplateSwiftDao;
import com.cloud.storage.dao.VMTemplateZoneDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeHostDao;

import com.cloud.storage.secondary.SecondaryStorageVmManager;
import com.cloud.storage.swift.SwiftManager;
import com.cloud.storage.template.TemplateConstants;
import com.cloud.storage.template.TemplateInfo;
import com.cloud.template.TemplateManager;
import com.cloud.user.Account;
import com.cloud.user.ResourceLimitService;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.SecondaryStorageVm;
import com.cloud.vm.SecondaryStorageVmVO;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.SecondaryStorageVmDao;
import com.cloud.vm.dao.UserVmDao;

import edu.emory.mathcs.backport.java.util.Collections;


@Component
@Local(value={DownloadMonitor.class})
public class DownloadMonitorImpl extends ManagerBase implements  DownloadMonitor {
    static final Logger s_logger = Logger.getLogger(DownloadMonitorImpl.class);
	
    @Inject 
    VMTemplateHostDao _vmTemplateHostDao;
    @Inject 
    VMTemplateZoneDao _vmTemplateZoneDao;
    @Inject
	VMTemplatePoolDao _vmTemplatePoolDao;
    @Inject
    VMTemplateSwiftDao _vmTemplateSwiftlDao;
    @Inject
    StoragePoolHostDao _poolHostDao;
    @Inject
    SecondaryStorageVmDao _secStorageVmDao;
    @Inject
    VolumeDao _volumeDao;
    @Inject
    VolumeHostDao _volumeHostDao;
    @Inject
    AlertManager _alertMgr;
    @Inject
    protected SwiftManager _swiftMgr;
    @Inject
    SecondaryStorageVmManager _ssvmMgr;
    @Inject
    StorageManager _storageMgr ;
    
    @Inject
    private final DataCenterDao _dcDao = null;
    @Inject
    VMTemplateDao _templateDao =  null;
    @Inject
	private AgentManager _agentMgr;
    @Inject SecondaryStorageVmManager _secMgr;
    @Inject
    ConfigurationDao _configDao;
    @Inject
    UserVmManager _vmMgr;

    @Inject TemplateManager templateMgr;

    
    @Inject 
    private UsageEventDao _usageEventDao;
    

    @Inject
    private ClusterDao _clusterDao;
    @Inject
    private HostDao _hostDao;
    @Inject
    private ResourceManager _resourceMgr;
    @Inject
    private SwiftDao _swiftDao;
    @Inject
    protected ResourceLimitService _resourceLimitMgr;
    @Inject
    protected UserVmDao _userVmDao;

	private Boolean _sslCopy = new Boolean(false);
	private String _copyAuthPasswd;
	private String _proxy = null;
    protected SearchBuilder<VMTemplateHostVO> ReadyTemplateStatesSearch;

	Timer _timer;

	final Map<VMTemplateHostVO, DownloadListener> _listenerMap = new ConcurrentHashMap<VMTemplateHostVO, DownloadListener>();
	final Map<VolumeHostVO, DownloadListener> _listenerVolumeMap = new ConcurrentHashMap<VolumeHostVO, DownloadListener>();


	public void send(Long hostId, Command cmd, Listener listener) throws AgentUnavailableException {
		_agentMgr.send(hostId, new Commands(cmd), listener);
	}

	@Override
	public boolean configure(String name, Map<String, Object> params) {
        final Map<String, String> configs = _configDao.getConfiguration("ManagementServer", params);
        _sslCopy = Boolean.parseBoolean(configs.get("secstorage.encrypt.copy"));
        _proxy = configs.get(Config.SecStorageProxy.key());
        
        String cert = configs.get("secstorage.ssl.cert.domain");
        if (!"realhostip.com".equalsIgnoreCase(cert)) {
        	s_logger.warn("Only realhostip.com ssl cert is supported, ignoring self-signed and other certs");
        }
        
        _copyAuthPasswd = configs.get("secstorage.copy.password");
        
        _agentMgr.registerForHostEvents(new DownloadListener(this), true, false, false);
        
        ReadyTemplateStatesSearch = _vmTemplateHostDao.createSearchBuilder();
        ReadyTemplateStatesSearch.and("download_state", ReadyTemplateStatesSearch.entity().getDownloadState(), SearchCriteria.Op.EQ);
        ReadyTemplateStatesSearch.and("destroyed", ReadyTemplateStatesSearch.entity().getDestroyed(), SearchCriteria.Op.EQ);
        ReadyTemplateStatesSearch.and("host_id", ReadyTemplateStatesSearch.entity().getHostId(), SearchCriteria.Op.EQ);

        SearchBuilder<VMTemplateVO> TemplatesWithNoChecksumSearch = _templateDao.createSearchBuilder();
        TemplatesWithNoChecksumSearch.and("checksum", TemplatesWithNoChecksumSearch.entity().getChecksum(), SearchCriteria.Op.NULL);

        ReadyTemplateStatesSearch.join("vm_template", TemplatesWithNoChecksumSearch, TemplatesWithNoChecksumSearch.entity().getId(),
                ReadyTemplateStatesSearch.entity().getTemplateId(), JoinBuilder.JoinType.INNER);
        TemplatesWithNoChecksumSearch.done();
        ReadyTemplateStatesSearch.done();
               
		return true;
	}

	@Override
	public boolean start() {
		_timer = new Timer();
		return true;
	}

	@Override
	public boolean stop() {
		return true;
	}
	
	public boolean isTemplateUpdateable(Long templateId, Long hostId) {
		List<VMTemplateHostVO> downloadsInProgress =
			_vmTemplateHostDao.listByTemplateHostStatus(templateId.longValue(), hostId.longValue(), VMTemplateHostVO.Status.DOWNLOAD_IN_PROGRESS, VMTemplateHostVO.Status.DOWNLOADED);
		return (downloadsInProgress.size() == 0);
	}
	
	@Override
    public boolean copyTemplate(VMTemplateVO template, HostVO sourceServer, HostVO destServer) throws StorageUnavailableException{

		boolean downloadJobExists = false;
        VMTemplateHostVO destTmpltHost = null;
        VMTemplateHostVO srcTmpltHost = null;

        srcTmpltHost = _vmTemplateHostDao.findByHostTemplate(sourceServer.getId(), template.getId());
        if (srcTmpltHost == null) {
        	throw new InvalidParameterValueException("Template " + template.getName() + " not associated with " + sourceServer.getName());
        }

        String url = generateCopyUrl(sourceServer, srcTmpltHost);
	    if (url == null) {
			s_logger.warn("Unable to start/resume copy of template " + template.getUniqueName() + " to " + destServer.getName() + ", no secondary storage vm in running state in source zone");
			throw new CloudRuntimeException("No secondary VM in running state in zone " + sourceServer.getDataCenterId());
	    }
        destTmpltHost = _vmTemplateHostDao.findByHostTemplate(destServer.getId(), template.getId());
        if (destTmpltHost == null) {
            destTmpltHost = new VMTemplateHostVO(destServer.getId(), template.getId(), new Date(), 0, VMTemplateStorageResourceAssoc.Status.NOT_DOWNLOADED, null, null, "jobid0000", null, url);
            destTmpltHost.setCopy(true);
            destTmpltHost.setPhysicalSize(srcTmpltHost.getPhysicalSize());
            _vmTemplateHostDao.persist(destTmpltHost);
        } else if ((destTmpltHost.getJobId() != null) && (destTmpltHost.getJobId().length() > 2)) {
            downloadJobExists = true;
        }

        Long maxTemplateSizeInBytes = getMaxTemplateSizeInBytes();
        if (srcTmpltHost.getSize() > maxTemplateSizeInBytes){
        	throw new CloudRuntimeException("Cant copy the template as the template's size " +srcTmpltHost.getSize()+
        			" is greater than max.template.iso.size " + maxTemplateSizeInBytes);
        }
        
		if(destTmpltHost != null) {
		    start();
            String sourceChecksum = this.templateMgr.getChecksum(srcTmpltHost.getHostId(), srcTmpltHost.getInstallPath());
			DownloadCommand dcmd =  
              new DownloadCommand(destServer.getStorageUrl(), url, template, TemplateConstants.DEFAULT_HTTP_AUTH_USER, _copyAuthPasswd, maxTemplateSizeInBytes); 
			dcmd.setProxy(getHttpProxy());
			if (downloadJobExists) {
				dcmd = new DownloadProgressCommand(dcmd, destTmpltHost.getJobId(), RequestType.GET_OR_RESTART);
	 		}
			dcmd.setChecksum(sourceChecksum); // We need to set the checksum as the source template might be a compressed url and have cksum for compressed image. Bug #10775
            HostVO ssAhost = _ssvmMgr.pickSsvmHost(destServer);
            if( ssAhost == null ) {
                 s_logger.warn("There is no secondary storage VM for secondary storage host " + destServer.getName());
                 return false;
            }
            DownloadListener dl = new DownloadListener(ssAhost, destServer, template, _timer, _vmTemplateHostDao, destTmpltHost.getId(), this, dcmd, _templateDao);
            if (downloadJobExists) {
                dl.setCurrState(destTmpltHost.getDownloadState());
            }
			DownloadListener old = null;
			synchronized (_listenerMap) {
			    old = _listenerMap.put(destTmpltHost, dl);
			}
			if( old != null ) {
			    old.abandon();
			}
			
            try {
	            send(ssAhost.getId(), dcmd, dl);
	            return true;
            } catch (AgentUnavailableException e) {
				s_logger.warn("Unable to start /resume COPY of template " + template.getUniqueName() + " to " + destServer.getName(), e);
				dl.setDisconnected();
				dl.scheduleStatusCheck(RequestType.GET_OR_RESTART);
	            e.printStackTrace();
            }
		}
		
		return false;
	}
	
	private String generateCopyUrl(String ipAddress, String dir, String path){
		String hostname = ipAddress;
		String scheme = "http";
		if (_sslCopy) {
			hostname = ipAddress.replace(".", "-");
			hostname = hostname + ".realhostip.com";
			scheme = "https";
		}
		return scheme + "://" + hostname + "/copy/SecStorage/" + dir + "/" + path; 
	}
	
	private String generateCopyUrl(HostVO sourceServer, VMTemplateHostVO srcTmpltHost) {
		List<SecondaryStorageVmVO> ssVms = _secStorageVmDao.getSecStorageVmListInStates(SecondaryStorageVm.Role.templateProcessor, sourceServer.getDataCenterId(), State.Running);
		if (ssVms.size() > 0) {
			SecondaryStorageVmVO ssVm = ssVms.get(0);
			if (ssVm.getPublicIpAddress() == null) {
				s_logger.warn("A running secondary storage vm has a null public ip?");
				return null;
			}
			return generateCopyUrl(ssVm.getPublicIpAddress(), sourceServer.getParent(), srcTmpltHost.getInstallPath());
		}
		
		VMTemplateVO tmplt = _templateDao.findById(srcTmpltHost.getTemplateId());
		HypervisorType hyperType = tmplt.getHypervisorType();
		/*No secondary storage vm yet*/
		if (hyperType != null && hyperType == HypervisorType.KVM) {
			return "file://" + sourceServer.getParent() + "/" + srcTmpltHost.getInstallPath();
		}
		return null;
	}

	private void downloadTemplateToStorage(VMTemplateVO template, HostVO sserver) {
		boolean downloadJobExists = false;
        VMTemplateHostVO vmTemplateHost = null;

        vmTemplateHost = _vmTemplateHostDao.findByHostTemplate(sserver.getId(), template.getId());
        if (vmTemplateHost == null) {
            vmTemplateHost = new VMTemplateHostVO(sserver.getId(), template.getId(), new Date(), 0, VMTemplateStorageResourceAssoc.Status.NOT_DOWNLOADED, null, null, "jobid0000", null, template.getUrl());
            _vmTemplateHostDao.persist(vmTemplateHost);
        } else if ((vmTemplateHost.getJobId() != null) && (vmTemplateHost.getJobId().length() > 2)) {
            downloadJobExists = true;
        }
                
        Long maxTemplateSizeInBytes = getMaxTemplateSizeInBytes();
        String secUrl = sserver.getStorageUrl();
		if(vmTemplateHost != null) {
		    start();
			DownloadCommand dcmd =
             new DownloadCommand(secUrl, template, maxTemplateSizeInBytes);
			dcmd.setProxy(getHttpProxy());
	        if (downloadJobExists) {
	            dcmd = new DownloadProgressCommand(dcmd, vmTemplateHost.getJobId(), RequestType.GET_OR_RESTART);
	        }
			if (vmTemplateHost.isCopy()) {
				dcmd.setCreds(TemplateConstants.DEFAULT_HTTP_AUTH_USER, _copyAuthPasswd);
			}
			HostVO ssAhost = _ssvmMgr.pickSsvmHost(sserver);
			
			if(ssAhost == null && sserver.getResource() != null && sserver.getResource().toLowerCase().contains("hyperv"))
				ssAhost = sserver;
				
			if( ssAhost == null ) {
	             s_logger.warn("There is no secondary storage VM for secondary storage host " + sserver.getName());
	             return;
			}
			DownloadListener dl = new DownloadListener(ssAhost, sserver, template, _timer, _vmTemplateHostDao, vmTemplateHost.getId(), this, dcmd, _templateDao);
			if (downloadJobExists) {
				dl.setCurrState(vmTemplateHost.getDownloadState());
	 		}
            DownloadListener old = null;
            synchronized (_listenerMap) {
                old = _listenerMap.put(vmTemplateHost, dl);
            }
            if( old != null ) {
                old.abandon();
            }

			try {
	            send(ssAhost.getId(), dcmd, dl);
            } catch (AgentUnavailableException e) {
				s_logger.warn("Unable to start /resume download of template " + template.getUniqueName() + " to " + sserver.getName(), e);
				dl.setDisconnected();
				dl.scheduleStatusCheck(RequestType.GET_OR_RESTART);
            }
		}
	}



	@Override
	public boolean downloadTemplateToStorage(VMTemplateVO template, Long zoneId) {
        List<DataCenterVO> dcs = new ArrayList<DataCenterVO>();       
        if (zoneId == null) {
            dcs.addAll(_dcDao.listAll());
        } else {
            dcs.add(_dcDao.findById(zoneId));
        }
        long templateId = template.getId();
        boolean isPublic = template.isFeatured() || template.isPublicTemplate(); 
        for ( DataCenterVO dc : dcs ) {
    	    List<HostVO> ssHosts = _ssvmMgr.listAllTypesSecondaryStorageHostsInOneZone(dc.getId());
    	    for ( HostVO ssHost : ssHosts ) {
        		if (isTemplateUpdateable(templateId, ssHost.getId())) {
       				initiateTemplateDownload(templateId, ssHost);
       				if (! isPublic ) {
       				    break;
       				}
        		}
    	    }
	    }
	    return true;
	}
	
	@Override
	public boolean downloadVolumeToStorage(VolumeVO volume, Long zoneId, String url, String checkSum, ImageFormat format) {
                                
	    List<HostVO> ssHosts = _ssvmMgr.listAllTypesSecondaryStorageHostsInOneZone(zoneId);
	    Collections.shuffle(ssHosts);
	    HostVO ssHost = ssHosts.get(0);
	    downloadVolumeToStorage(volume, ssHost, url, checkSum, format);
	    return true;
	}
	
	private void downloadVolumeToStorage(VolumeVO volume, HostVO sserver, String url, String checkSum, ImageFormat format) {
		boolean downloadJobExists = false;
        VolumeHostVO volumeHost = null;

        volumeHost = _volumeHostDao.findByHostVolume(sserver.getId(), volume.getId());
        if (volumeHost == null) {
            volumeHost = new VolumeHostVO(sserver.getId(), volume.getId(), sserver.getDataCenterId(), new Date(), 0, VMTemplateStorageResourceAssoc.Status.NOT_DOWNLOADED, null, null,
            		"jobid0000", null, url, checkSum, format);
            _volumeHostDao.persist(volumeHost);
        } else if ((volumeHost.getJobId() != null) && (volumeHost.getJobId().length() > 2)) {
            downloadJobExists = true;
        }
        

        Long maxVolumeSizeInBytes = getMaxVolumeSizeInBytes();
        String secUrl = sserver.getStorageUrl();
		if(volumeHost != null) {
		    start();
			DownloadCommand dcmd = new DownloadCommand(secUrl, volume, maxVolumeSizeInBytes, checkSum, url, format);
			dcmd.setProxy(getHttpProxy());
	        if (downloadJobExists) {
	            dcmd = new DownloadProgressCommand(dcmd, volumeHost.getJobId(), RequestType.GET_OR_RESTART);
	            dcmd.setResourceType(ResourceType.VOLUME);
	        }
			
			HostVO ssvm = _ssvmMgr.pickSsvmHost(sserver);
			if( ssvm == null ) {
	             s_logger.warn("There is no secondary storage VM for secondary storage host " + sserver.getName());
	             return;
			}
			DownloadListener dl = new DownloadListener(ssvm, sserver, volume, _timer, _volumeHostDao, volumeHost.getId(),
					this, dcmd, _volumeDao, _storageMgr);
			
			if (downloadJobExists) {
				dl.setCurrState(volumeHost.getDownloadState());
	 		}
            DownloadListener old = null;
            synchronized (_listenerVolumeMap) {
                old = _listenerVolumeMap.put(volumeHost, dl);
            }
            if( old != null ) {
                old.abandon();
            }

			try {
	            send(ssvm.getId(), dcmd, dl);
            } catch (AgentUnavailableException e) {
				s_logger.warn("Unable to start /resume download of volume " + volume.getName() + " to " + sserver.getName(), e);
				dl.setDisconnected();
				dl.scheduleStatusCheck(RequestType.GET_OR_RESTART);
            }
		}
	}


	private void initiateTemplateDownload(Long templateId, HostVO ssHost) {
		VMTemplateVO template = _templateDao.findById(templateId);
		if (template != null && (template.getUrl() != null)) {
			//find all storage hosts and tell them to initiate download
    		downloadTemplateToStorage(template, ssHost);
		}
		
	}

	@DB
	public void handleDownloadEvent(HostVO host, VMTemplateVO template, Status dnldStatus) {
		if ((dnldStatus == VMTemplateStorageResourceAssoc.Status.DOWNLOADED) || (dnldStatus==Status.ABANDONED)){
			VMTemplateHostVO vmTemplateHost = new VMTemplateHostVO(host.getId(), template.getId());
	        synchronized (_listenerMap) {
	            _listenerMap.remove(vmTemplateHost);
	        }
		}
		
		VMTemplateHostVO vmTemplateHost = _vmTemplateHostDao.findByHostTemplate(host.getId(), template.getId());
		
		Transaction txn = Transaction.currentTxn();
        txn.start();		

        if (dnldStatus == Status.DOWNLOADED) {
            long size = -1;
            if(vmTemplateHost!=null){
                size = vmTemplateHost.getPhysicalSize();
                template.setSize(size);
                this._templateDao.update(template.getId(), template);
            }
            else{
                s_logger.warn("Failed to get size for template" + template.getName());
            }
            String eventType = EventTypes.EVENT_TEMPLATE_CREATE;
            if((template.getFormat()).equals(ImageFormat.ISO)){
                eventType = EventTypes.EVENT_ISO_CREATE;
            }
            if(template.getAccountId() != Account.ACCOUNT_ID_SYSTEM){
                UsageEventUtils.publishUsageEvent(eventType, template.getAccountId(), host.getDataCenterId(),
                        template.getId(), template.getName(), null, template.getSourceTemplateId(), size,
                        template.getClass().getName(), template.getUuid());
            }
        }
        txn.commit();
	}

	@DB
	public void handleDownloadEvent(HostVO host, VolumeVO volume, Status dnldStatus) {
		if ((dnldStatus == VMTemplateStorageResourceAssoc.Status.DOWNLOADED) || (dnldStatus==Status.ABANDONED)){
			VolumeHostVO volumeHost = new VolumeHostVO(host.getId(), volume.getId());
	        synchronized (_listenerVolumeMap) {
	        	_listenerVolumeMap.remove(volumeHost);
	        }
		}
		
		VolumeHostVO volumeHost = _volumeHostDao.findByHostVolume(host.getId(), volume.getId());
		
		Transaction txn = Transaction.currentTxn();
        txn.start();		

        if (dnldStatus == Status.DOWNLOADED) {
			
			//Create usage event
            long size = -1;
            if(volumeHost!=null){
                size = volumeHost.getPhysicalSize();
                volume.setSize(size);
                this._volumeDao.update(volume.getId(), volume);
            }
            else{
                s_logger.warn("Failed to get size for volume" + volume.getName());
            }
            String eventType = EventTypes.EVENT_VOLUME_UPLOAD;            
            if(volume.getAccountId() != Account.ACCOUNT_ID_SYSTEM){
                UsageEventUtils.publishUsageEvent(eventType, volume.getAccountId(), host.getDataCenterId(),
                        volume.getId(), volume.getName(), null, 0l, size, volume.getClass().getName(), volume.getUuid());
            }
        }else if (dnldStatus == Status.DOWNLOAD_ERROR || dnldStatus == Status.ABANDONED || dnldStatus == Status.UNKNOWN){
            //Decrement the volume count
        	_resourceLimitMgr.decrementResourceCount(volume.getAccountId(), com.cloud.configuration.Resource.ResourceType.volume);
        }
        txn.commit();		
	}
	
	@Override
    public void handleSysTemplateDownload(HostVO host) {
	    List<HypervisorType> hypers = _resourceMgr.listAvailHypervisorInZone(host.getId(), host.getDataCenterId());
	    HypervisorType hostHyper = host.getHypervisorType();
	    if (hypers.contains(hostHyper)) {
	        return;
	    }

	    Set<VMTemplateVO> toBeDownloaded = new HashSet<VMTemplateVO>();
	    List<HostVO> ssHosts = _resourceMgr.listAllUpAndEnabledHostsInOneZoneByType(Host.Type.SecondaryStorage, host.getDataCenterId());
	    if (ssHosts == null || ssHosts.isEmpty()) {
	        return;
	    }
	    /*Download all the templates in zone with the same hypervisortype*/
        for ( HostVO ssHost : ssHosts) {
    	    List<VMTemplateVO> rtngTmplts = _templateDao.listAllSystemVMTemplates();
    	    List<VMTemplateVO> defaultBuiltin = _templateDao.listDefaultBuiltinTemplates();
    
    
    	    for (VMTemplateVO rtngTmplt : rtngTmplts) {
    	        if (rtngTmplt.getHypervisorType() == hostHyper) {
    	            toBeDownloaded.add(rtngTmplt);
    	        }
    	    }
    
    	    for (VMTemplateVO builtinTmplt : defaultBuiltin) {
    	        if (builtinTmplt.getHypervisorType() == hostHyper) {
    	            toBeDownloaded.add(builtinTmplt);
    	        }
    	    }
    
    	    for (VMTemplateVO template: toBeDownloaded) {
    	        VMTemplateHostVO tmpltHost = _vmTemplateHostDao.findByHostTemplate(ssHost.getId(), template.getId());
    	        if (tmpltHost == null || tmpltHost.getDownloadState() != Status.DOWNLOADED) {
    	            downloadTemplateToStorage(template, ssHost);
    	        }
    	    }
        }
	}
    
    @Override	
	public void addSystemVMTemplatesToHost(HostVO host, Map<String, TemplateInfo> templateInfos){
	    if ( templateInfos == null ) {
	        return;
	    }
	    Long hostId = host.getId();
	    List<VMTemplateVO> rtngTmplts = _templateDao.listAllSystemVMTemplates();
	    for ( VMTemplateVO tmplt : rtngTmplts ) {
	        TemplateInfo tmpltInfo = templateInfos.get(tmplt.getUniqueName());
	        if ( tmpltInfo == null ) {
	            continue;
	        }
	        VMTemplateHostVO tmpltHost = _vmTemplateHostDao.findByHostTemplate(hostId, tmplt.getId());
	        if ( tmpltHost == null ) {
                tmpltHost = new VMTemplateHostVO(hostId, tmplt.getId(), new Date(), 100, Status.DOWNLOADED, null, null, null, tmpltInfo.getInstallPath(), tmplt.getUrl());
	            tmpltHost.setSize(tmpltInfo.getSize());
	            tmpltHost.setPhysicalSize(tmpltInfo.getPhysicalSize());
	            _vmTemplateHostDao.persist(tmpltHost);
	        }
	    }
	}
	
    @Override
    public void handleSync(Long dcId) {
        if (dcId != null) {
            List<HostVO> ssHosts = _ssvmMgr.listSecondaryStorageHostsInOneZone(dcId);
            for (HostVO ssHost : ssHosts) {
                handleTemplateSync(ssHost);
                handleVolumeSync(ssHost);
            }
        }
    }
    
    private Map<String, TemplateInfo> listTemplate(HostVO ssHost) {
        ListTemplateCommand cmd = new ListTemplateCommand(ssHost.getStorageUrl());
        Answer answer = _agentMgr.sendToSecStorage(ssHost, cmd);
        if (answer != null && answer.getResult()) {
            ListTemplateAnswer tanswer = (ListTemplateAnswer)answer;
            return tanswer.getTemplateInfo();
        } else {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("can not list template for secondary storage host " + ssHost.getId());
            }
        } 
        
        return null;
    }
    
    private Map<Long, TemplateInfo> listVolume(HostVO ssHost) {
    	ListVolumeCommand cmd = new ListVolumeCommand(ssHost.getStorageUrl());
        Answer answer = _agentMgr.sendToSecStorage(ssHost, cmd);
        if (answer != null && answer.getResult()) {
        	ListVolumeAnswer tanswer = (ListVolumeAnswer)answer;
            return tanswer.getTemplateInfo();
        } else {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Can not list volumes for secondary storage host " + ssHost.getId());
            }
        } 
        
        return null;
    }
    
    private Map<String, TemplateInfo> listTemplate(SwiftVO swift) {
        if (swift == null) {
            return null;
        }
        ListTemplateCommand cmd = new ListTemplateCommand(swift.toSwiftTO());
        Answer answer = _agentMgr.sendToSSVM(null, cmd);
        if (answer != null && answer.getResult()) {
            ListTemplateAnswer tanswer = (ListTemplateAnswer) answer;
            return tanswer.getTemplateInfo();
        } else {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("can not list template for swift " + swift);
            }
        }
        return null;
    }
    
    @Override
    public void handleVolumeSync(HostVO ssHost) {
        if (ssHost == null) {
            s_logger.warn("Huh? ssHost is null");
            return;
        }
        long sserverId = ssHost.getId();        
        if (!(ssHost.getType() == Host.Type.SecondaryStorage || ssHost.getType() == Host.Type.LocalSecondaryStorage)) {
            s_logger.warn("Huh? Agent id " + sserverId + " is not secondary storage host");
            return;
        }

        Map<Long, TemplateInfo> volumeInfos = listVolume(ssHost);
        if (volumeInfos == null) {
            return;
        }
        
        List<VolumeHostVO> dbVolumes = _volumeHostDao.listBySecStorage(sserverId);
        List<VolumeHostVO> toBeDownloaded = new ArrayList<VolumeHostVO>(dbVolumes);
        for (VolumeHostVO volumeHost : dbVolumes){
        	VolumeVO volume = _volumeDao.findById(volumeHost.getVolumeId());
        	//Exists then don't download
        	if (volumeInfos.containsKey(volume.getId())){
                TemplateInfo volInfo = volumeInfos.remove(volume.getId());
                toBeDownloaded.remove(volumeHost);
                s_logger.info("Volume Sync found " + volume.getUuid() + " already in the volume host table");
                if (volumeHost.getDownloadState() != Status.DOWNLOADED) {
                	volumeHost.setErrorString("");
                }
                if (volInfo.isCorrupted()) {
                	volumeHost.setDownloadState(Status.DOWNLOAD_ERROR);
                    String msg = "Volume " + volume.getUuid() + " is corrupted on secondary storage ";
                    volumeHost.setErrorString(msg);
                    s_logger.info("msg");
                    if (volumeHost.getDownloadUrl() == null) {
                        msg = "Volume (" + volume.getUuid() + ") with install path " + volInfo.getInstallPath() + "is corrupted, please check in secondary storage: " + volumeHost.getHostId();
                        s_logger.warn(msg);
                    } else {
                        toBeDownloaded.add(volumeHost);
                    }

                } else { // Put them in right status
                    volumeHost.setDownloadPercent(100);
                    volumeHost.setDownloadState(Status.DOWNLOADED);
                    volumeHost.setInstallPath(volInfo.getInstallPath());
                    volumeHost.setSize(volInfo.getSize());
                    volumeHost.setPhysicalSize(volInfo.getPhysicalSize());
                    volumeHost.setLastUpdated(new Date());
                    _volumeHostDao.update(volumeHost.getId(), volumeHost);

                    if (volume.getSize() == 0) {
                        // Set volume size in volumes table
                        volume.setSize(volInfo.getSize());
                        _volumeDao.update(volumeHost.getVolumeId(), volume);
                    }
                }
                continue;
        	}
        	// Volume is not on secondary but we should download.
        	if (volumeHost.getDownloadState() != Status.DOWNLOADED) {
                s_logger.info("Volume Sync did not find " + volume.getName() + " ready on server " + sserverId + ", will request download to start/resume shortly");
                toBeDownloaded.add(volumeHost);
            } 
        }
        
        //Download volumes which haven't been downloaded yet.
        if (toBeDownloaded.size() > 0) {
            for (VolumeHostVO volumeHost : toBeDownloaded) {
                if (volumeHost.getDownloadUrl() == null) { // If url is null we can't initiate the download
                    continue;
                }                                  
                s_logger.debug("Volume " + volumeHost.getVolumeId() + " needs to be downloaded to " + ssHost.getName());
                downloadVolumeToStorage(_volumeDao.findById(volumeHost.getVolumeId()), ssHost,  volumeHost.getDownloadUrl(), volumeHost.getChecksum(), volumeHost.getFormat());                
            }
        }

        //Delete volumes which are not present on DB.
        for (Long uniqueName : volumeInfos.keySet()) {
            TemplateInfo vInfo = volumeInfos.get(uniqueName);
            DeleteVolumeCommand dtCommand = new DeleteVolumeCommand(ssHost.getStorageUrl(), vInfo.getInstallPath());
            try {
	            _agentMgr.sendToSecStorage(ssHost, dtCommand, null);
            } catch (AgentUnavailableException e) {
                String err = "Failed to delete " + vInfo.getTemplateName() + " on secondary storage " + sserverId + " which isn't in the database";
                s_logger.error(err);
                return;
            }
            
            String description = "Deleted volume " + vInfo.getTemplateName() + " on secondary storage " + sserverId + " since it isn't in the database";
            s_logger.info(description);
        }
    }
    
	@Override
    public void handleTemplateSync(HostVO ssHost) {
        if (ssHost == null) {
            s_logger.warn("Huh? ssHost is null");
            return;
        }
        long sserverId = ssHost.getId();
        long zoneId = ssHost.getDataCenterId();
        if (!(ssHost.getType() == Host.Type.SecondaryStorage || ssHost.getType() == Host.Type.LocalSecondaryStorage)) {
            s_logger.warn("Huh? Agent id " + sserverId + " is not secondary storage host");
            return;
        }

        Map<String, TemplateInfo> templateInfos = listTemplate(ssHost);
        if (templateInfos == null) {
            return;
        }

        Set<VMTemplateVO> toBeDownloaded = new HashSet<VMTemplateVO>();
        List<VMTemplateVO> allTemplates = _templateDao.listAllInZone(zoneId);
        List<VMTemplateVO> rtngTmplts = _templateDao.listAllSystemVMTemplates();
        List<VMTemplateVO> defaultBuiltin = _templateDao.listDefaultBuiltinTemplates();

        if (rtngTmplts != null) {
            for (VMTemplateVO rtngTmplt : rtngTmplts) {
                if (!allTemplates.contains(rtngTmplt)) {
                    allTemplates.add(rtngTmplt);
                }
            }
        }

        if (defaultBuiltin != null) {
            for (VMTemplateVO builtinTmplt : defaultBuiltin) {
                if (!allTemplates.contains(builtinTmplt)) {
                    allTemplates.add(builtinTmplt);
                }
            }
        }

        toBeDownloaded.addAll(allTemplates);

        for (VMTemplateVO tmplt : allTemplates) {
            String uniqueName = tmplt.getUniqueName();
            VMTemplateHostVO tmpltHost = _vmTemplateHostDao.findByHostTemplate(sserverId, tmplt.getId());
            if (templateInfos.containsKey(uniqueName)) {
                TemplateInfo tmpltInfo = templateInfos.remove(uniqueName);
                toBeDownloaded.remove(tmplt);
                if (tmpltHost != null) {
                    s_logger.info("Template Sync found " + uniqueName + " already in the template host table");
                    if (tmpltHost.getDownloadState() != Status.DOWNLOADED) {
                        tmpltHost.setErrorString("");
                    }
                    if (tmpltInfo.isCorrupted()) {
                        tmpltHost.setDownloadState(Status.DOWNLOAD_ERROR);
                        String msg = "Template " + tmplt.getName() + ":" + tmplt.getId() + " is corrupted on secondary storage " + tmpltHost.getId();
                        tmpltHost.setErrorString(msg);
                        s_logger.info("msg");
                        if (tmplt.getUrl() == null) {
                            msg = "Private Template (" + tmplt + ") with install path " + tmpltInfo.getInstallPath() + "is corrupted, please check in secondary storage: " + tmpltHost.getHostId();
                            s_logger.warn(msg);
                        } else {
                            toBeDownloaded.add(tmplt);
                        }

                    } else {
                        tmpltHost.setDownloadPercent(100);
                        tmpltHost.setDownloadState(Status.DOWNLOADED);
                        tmpltHost.setInstallPath(tmpltInfo.getInstallPath());
                        tmpltHost.setSize(tmpltInfo.getSize());
                        tmpltHost.setPhysicalSize(tmpltInfo.getPhysicalSize());
                        tmpltHost.setLastUpdated(new Date());
                    }
                    _vmTemplateHostDao.update(tmpltHost.getId(), tmpltHost);
                } else {
                    tmpltHost = new VMTemplateHostVO(sserverId, tmplt.getId(), new Date(), 100, Status.DOWNLOADED, null, null, null, tmpltInfo.getInstallPath(), tmplt.getUrl());
                    tmpltHost.setSize(tmpltInfo.getSize());
                    tmpltHost.setPhysicalSize(tmpltInfo.getPhysicalSize());
                    _vmTemplateHostDao.persist(tmpltHost);
                    VMTemplateZoneVO tmpltZoneVO = _vmTemplateZoneDao.findByZoneTemplate(zoneId, tmplt.getId());
                    if (tmpltZoneVO == null) {
                        tmpltZoneVO = new VMTemplateZoneVO(zoneId, tmplt.getId(), new Date());
                        _vmTemplateZoneDao.persist(tmpltZoneVO);
                    } else {
                        tmpltZoneVO.setLastUpdated(new Date());
                        _vmTemplateZoneDao.update(tmpltZoneVO.getId(), tmpltZoneVO);
                    }

                }

                continue;
            }
            if (tmpltHost != null && tmpltHost.getDownloadState() != Status.DOWNLOADED) {
                s_logger.info("Template Sync did not find " + uniqueName + " ready on server " + sserverId + ", will request download to start/resume shortly");

            } else if (tmpltHost == null) {
                s_logger.info("Template Sync did not find " + uniqueName + " on the server " + sserverId + ", will request download shortly");
                VMTemplateHostVO templtHost = new VMTemplateHostVO(sserverId, tmplt.getId(), new Date(), 0, Status.NOT_DOWNLOADED, null, null, null, null, tmplt.getUrl());
                _vmTemplateHostDao.persist(templtHost);
                VMTemplateZoneVO tmpltZoneVO = _vmTemplateZoneDao.findByZoneTemplate(zoneId, tmplt.getId());
                if (tmpltZoneVO == null) {
                    tmpltZoneVO = new VMTemplateZoneVO(zoneId, tmplt.getId(), new Date());
                    _vmTemplateZoneDao.persist(tmpltZoneVO);
                } else {
                    tmpltZoneVO.setLastUpdated(new Date());
                    _vmTemplateZoneDao.update(tmpltZoneVO.getId(), tmpltZoneVO);
                }
            }

        }

        if (toBeDownloaded.size() > 0) {
            /* Only download templates whose hypervirsor type is in the zone */
            List<HypervisorType> availHypers = _clusterDao.getAvailableHypervisorInZone(zoneId);
            if (availHypers.isEmpty()) {
                /*
                 * This is for cloudzone, local secondary storage resource
                 * started before cluster created
                 */
                availHypers.add(HypervisorType.KVM);
            }
            /* Baremetal need not to download any template */
            availHypers.remove(HypervisorType.BareMetal);
            availHypers.add(HypervisorType.None); // bug 9809: resume ISO
                                                  // download.
            for (VMTemplateVO tmplt : toBeDownloaded) {
                if (tmplt.getUrl() == null) { // If url is null we can't
                                              // initiate the download
                    continue;
                }
                // if this is private template, and there is no record for this
                // template in this sHost, skip
                if (!tmplt.isPublicTemplate() && !tmplt.isFeatured()) {
                    VMTemplateHostVO tmpltHost = _vmTemplateHostDao.findByHostTemplate(sserverId, tmplt.getId());
                    if (tmpltHost == null) {
                        continue;
                    }
                }
                if (availHypers.contains(tmplt.getHypervisorType())) {
                    if (_swiftMgr.isSwiftEnabled()) {
                        if (_swiftMgr.isTemplateInstalled(tmplt.getId())) {
                            continue;
                        }
                    }
                    s_logger.debug("Template " + tmplt.getName() + " needs to be downloaded to " + ssHost.getName());
                    downloadTemplateToStorage(tmplt, ssHost);
                }
            }
        }

        for (String uniqueName : templateInfos.keySet()) {
            TemplateInfo tInfo = templateInfos.get(uniqueName);
            List<UserVmVO> userVmUsingIso = _userVmDao.listByIsoId(tInfo.getId());
            //check if there is any Vm using this ISO.
            if (userVmUsingIso == null || userVmUsingIso.isEmpty()) {
                DeleteTemplateCommand dtCommand = new DeleteTemplateCommand(ssHost.getStorageUrl(), tInfo.getInstallPath());
                try {
                    _agentMgr.sendToSecStorage(ssHost, dtCommand, null);
                } catch (AgentUnavailableException e) {
                    String err = "Failed to delete " + tInfo.getTemplateName() + " on secondary storage " + sserverId + " which isn't in the database";
                    s_logger.error(err);
                    return;
                }

                String description = "Deleted template " + tInfo.getTemplateName() + " on secondary storage " + sserverId + " since it isn't in the database";
                s_logger.info(description);
            }
        }
    }

	@Override
	public void cancelAllDownloads(Long templateId) {
		List<VMTemplateHostVO> downloadsInProgress =
			_vmTemplateHostDao.listByTemplateStates(templateId, VMTemplateHostVO.Status.DOWNLOAD_IN_PROGRESS, VMTemplateHostVO.Status.NOT_DOWNLOADED);
		if (downloadsInProgress.size() > 0){
			for (VMTemplateHostVO vmthvo: downloadsInProgress) {
			    DownloadListener dl = null;
		        synchronized (_listenerMap) {
				    dl = _listenerMap.remove(vmthvo);
		        }
				if (dl != null) {
					dl.abandon();
					s_logger.info("Stopping download of template " + templateId + " to storage server " + vmthvo.getHostId());
				}
			}
		}
	}
	
	private void checksumSync(long hostId){
        SearchCriteria<VMTemplateHostVO> sc = ReadyTemplateStatesSearch.create();
        sc.setParameters("download_state", com.cloud.storage.VMTemplateStorageResourceAssoc.Status.DOWNLOADED);
        sc.setParameters("host_id", hostId);

        List<VMTemplateHostVO> templateHostRefList = _vmTemplateHostDao.search(sc, null);
        s_logger.debug("Found " +templateHostRefList.size()+ " templates with no checksum. Will ask for computation");
        for(VMTemplateHostVO templateHostRef : templateHostRefList){
            s_logger.debug("Getting checksum for template - " + templateHostRef.getTemplateId());
            String checksum = this.templateMgr.getChecksum(hostId, templateHostRef.getInstallPath());
            VMTemplateVO template = _templateDao.findById(templateHostRef.getTemplateId());
            s_logger.debug("Setting checksum " +checksum+ " for template - " + template.getName());
            template.setChecksum(checksum);
            _templateDao.update(template.getId(), template);
        }

	}
	
	private Long getMaxTemplateSizeInBytes() {
		try {
			return Long.parseLong(_configDao.getValue("max.template.iso.size")) * 1024L * 1024L * 1024L;
		} catch (NumberFormatException e) {
			return null;
		}
	}
	
	private Long getMaxVolumeSizeInBytes() {
		try {
			return Long.parseLong(_configDao.getValue("storage.max.volume.upload.size")) * 1024L * 1024L * 1024L;
		} catch (NumberFormatException e) {
			return null;
		}
	}
	
	private Proxy getHttpProxy() {
		if (_proxy == null) {
			return null;
		}
		try {
			URI uri = new URI(_proxy);
			Proxy prx = new Proxy(uri);
			return prx;
		} catch (URISyntaxException e) {
			return null;
		}
	}	
	
}
	
