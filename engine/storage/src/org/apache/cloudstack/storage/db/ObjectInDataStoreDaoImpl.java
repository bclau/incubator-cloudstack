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
package org.apache.cloudstack.storage.db;
import java.util.Date;

import org.springframework.stereotype.Component;

import org.apache.cloudstack.storage.volume.ObjectInDataStoreStateMachine.Event;
import org.apache.cloudstack.storage.volume.ObjectInDataStoreStateMachine.State;
import org.apache.cloudstack.storage.volume.db.VolumeVO;
import org.apache.log4j.Logger;

import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria2;
import com.cloud.utils.db.SearchCriteriaService;
import com.cloud.utils.db.UpdateBuilder;

@Component
public class ObjectInDataStoreDaoImpl extends GenericDaoBase<ObjectInDataStoreVO, Long> implements ObjectInDataStoreDao {
    private static final Logger s_logger = Logger.getLogger(ObjectInDataStoreDaoImpl.class);
    @Override
    public boolean updateState(State currentState, Event event,
            State nextState, ObjectInDataStoreVO vo, Object data) {
        Long oldUpdated = vo.getUpdatedCount();
        Date oldUpdatedTime = vo.getUpdated();
    
        SearchCriteria<ObjectInDataStoreVO> sc = this.createSearchCriteria();
        sc.setParameters("id", vo.getId());
        sc.setParameters("state", currentState);
        sc.setParameters("updatedCount", vo.getUpdatedCount());

        vo.incrUpdatedCount();

        UpdateBuilder builder = getUpdateBuilder(vo);
        builder.set(vo, "state", nextState);
        builder.set(vo, "updated", new Date());

        int rows = update((ObjectInDataStoreVO) vo, sc);
        if (rows == 0 && s_logger.isDebugEnabled()) {
            ObjectInDataStoreVO dbVol = findByIdIncludingRemoved(vo.getId());
            if (dbVol != null) {
                StringBuilder str = new StringBuilder("Unable to update ").append(vo.toString());
                str.append(": DB Data={id=").append(dbVol.getId()).append("; state=").append(dbVol.getState()).append("; updatecount=").append(dbVol.getUpdatedCount()).append(";updatedTime=")
                        .append(dbVol.getUpdated());
                str.append(": New Data={id=").append(vo.getId()).append("; state=").append(nextState).append("; event=").append(event).append("; updatecount=").append(vo.getUpdatedCount())
                        .append("; updatedTime=").append(vo.getUpdated());
                str.append(": stale Data={id=").append(vo.getId()).append("; state=").append(currentState).append("; event=").append(event).append("; updatecount=").append(oldUpdated)
                        .append("; updatedTime=").append(oldUpdatedTime);
            } else {
                s_logger.debug("Unable to update objectIndatastore: id=" + vo.getId() + ", as there is no such object exists in the database anymore");
            }
        }
        return rows > 0;
    }

}