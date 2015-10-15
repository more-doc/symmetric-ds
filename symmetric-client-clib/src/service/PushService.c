/**
 * Licensed to JumpMind Inc under one or more contributor
 * license agreements.  See the NOTICE file distributed
 * with this work for additional information regarding
 * copyright ownership.  JumpMind Inc licenses this file
 * to you under the GNU General Public License, version 3.0 (GPLv3)
 * (the "License"); you may not use this file except in compliance
 * with the License.
 *
 * You should have received a copy of the GNU General Public License,
 * version 3.0 (GPLv3) along with this library; if not, see
 * <http://www.gnu.org/licenses/>.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
#include "service/PushService.h"

void SymPushService_pushToNode(SymPushService *this, SymNode *remote, SymRemoteNodeStatus *status) {
    // TODO: Node identity = nodeService.findIdentity(false);
    SymNode *identity = this->nodeService->findIdentity(this->nodeService);
    SymNodeSecurity *identitySecurity = this->nodeService->findNodeSecurity(this->nodeService, identity->nodeId);
    SymOutgoingTransport *transport = this->transportManager->getPushTransport(this->transportManager, remote, identity, identitySecurity->nodePassword,
            this->parameterService->getRegistrationUrl(this->parameterService));
    SymList *extractedBatches = this->dataExtractorService->extract(this->dataExtractorService, remote, transport);
    if (extractedBatches->size > 0) {
        SymLog_info("Push data sent to %s:%s:%s", remote->nodeGroupId, remote->externalId, remote->nodeId);

        // TODO: read acknowledgement
        //SymList *batchAcks = readAcks(extractedBatches, transport, transportManager, acknowledgeService);
        //status->updateOutgoingStatus(status, extractedBatches, batchAcks);
        //batchAcks->destroy(batchAcks);
    }
    extractedBatches->destroy(extractedBatches);
    transport->destroy(transport);
    identity->destroy(identity);
    identitySecurity->destroy(identitySecurity);
}


void SymPushService_execute(SymPushService *this, SymNode *node, SymRemoteNodeStatus *status) {
    long reloadBatchesProcessed = 0;
    long lastBatchCount = 0;
    do {
        if (lastBatchCount > 0) {
            SymLog_info("Pushing to %s:%s:%s again because the last push contained reload batches",
                    node->nodeGroupId, node->externalId, node->nodeId);
        }
        reloadBatchesProcessed = status->reloadBatchesProcessed;
        SymLog_debug("Push requested for %s:%s:%s", node->nodeGroupId, node->externalId, node->nodeId);
        SymPushService_pushToNode(this, node, status);
        if (!status->failed && status->batchesProcessed > 0
                && status->batchesProcessed != lastBatchCount) {
            SymLog_info("Pushed data to %s:%s:%s. %ld data and %ld batches were processed",
                    node->nodeGroupId, node->externalId, node->nodeId, status->dataProcessed, status->batchesProcessed);
        } else if (status->failed) {
            SymLog_info("There was a failure while pushing data to %s:%s:%s. {} data and {} batches were processed",
                    node->nodeGroupId, node->externalId, node->nodeId, status->dataProcessed, status->batchesProcessed);
        }
        SymLog_debug("Push completed for %s:%s:%s", node->nodeGroupId, node->externalId, node->nodeId);
        lastBatchCount = status->batchesProcessed;
    } while (status->reloadBatchesProcessed > reloadBatchesProcessed && !status->failed);
}

SymRemoteNodeStatuses * SymPushService_pushData(SymPushService *this, unsigned int force) {
    SymMap *channels = this->configurationService->getChannels(this->configurationService, 0);
    SymRemoteNodeStatuses *statuses = SymRemoteNodeStatuses_new(NULL, channels);
    SymNode *identity = this->nodeService->findIdentity(this->nodeService);
    if (identity) {
        if (identity->syncEnabled) {
            SymList *nodes = this->nodeService->findNodesToPushTo(this->nodeService);
            if (nodes->size > 0) {
                SymNodeSecurity *identitySecurity = this->nodeService->findNodeSecurity(this->nodeService, identity->nodeId);
                if (identitySecurity) {
                    statuses = SymRemoteNodeStatuses_new(NULL, channels);
                    SymIterator *iter = nodes->iterator(nodes);
                    while (iter->hasNext(iter)) {
                        SymNode *node = (SymNode *) iter->next(iter);
                        SymRemoteNodeStatus *status = statuses->add(statuses, node->nodeId);
                        SymPushService_execute(this, node, status);
                    }
                    identitySecurity->destroy(identitySecurity);
                    iter->destroy(iter);
                } else {
                    SymLog_error("Could not find a node security row for '%s'.  A node needs a matching security row in both the local and remote nodes if it is going to authenticate to push data",
                            identity->nodeId);
                }
            }
            nodes->destroy(nodes);
        }
        identity->destroy(identity);
    }
    return statuses;
}

void SymPushService_destroy(SymPushService *this) {
    free(this);
}

SymPushService * SymPushService_new(SymPushService *this, SymNodeService *nodeService, SymDataExtractorService *dataExtractorService,
        SymTransportManager *transportManager, SymParameterService *parameterService, SymConfigurationService *configurationService) {
    if (this == NULL) {
        this = (SymPushService *) calloc(1, sizeof(SymPushService));
    }
    this->nodeService = nodeService;
    this->dataExtractorService = dataExtractorService;
    this->transportManager = transportManager;
    this->parameterService = parameterService;
    this->configurationService = configurationService;
    this->pushData = (void *) &SymPushService_pushData;
    this->destroy = (void *) &SymPushService_destroy;
    return this;
}
