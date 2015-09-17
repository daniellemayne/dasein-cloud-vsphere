package org.dasein.cloud.vsphere.compute;

import com.vmware.vim25.*;

import org.apache.log4j.Logger;
import org.codehaus.jackson.annotate.JsonAutoDetect;
import org.codehaus.jackson.annotate.JsonMethod;
import org.codehaus.jackson.map.ObjectMapper;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.InternalException;
import org.dasein.cloud.OperationNotSupportedException;
import org.dasein.cloud.ProviderContext;
import org.dasein.cloud.compute.AbstractAffinityGroupSupport;
import org.dasein.cloud.compute.AffinityGroup;
import org.dasein.cloud.compute.AffinityGroupCreateOptions;
import org.dasein.cloud.compute.AffinityGroupFilterOptions;
import org.dasein.cloud.dc.DataCenter;
import org.dasein.cloud.dc.DataCenterServices;
import org.dasein.cloud.util.APITrace;
import org.dasein.cloud.util.Cache;
import org.dasein.cloud.util.CacheLevel;
import org.dasein.cloud.vsphere.*;
import org.dasein.util.uom.time.Day;
import org.dasein.util.uom.time.TimePeriod;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * User: daniellemayne
 * Date: 11/09/2015
 * Time: 15:05
 */
public class HostSupport extends AbstractAffinityGroupSupport<Vsphere> {
    static private final Logger logger = Vsphere.getLogger(HostSupport.class);

    private Vsphere provider;

    protected HostSupport(@Nonnull Vsphere provider) {
        super(provider);
        this.provider = provider;
    }

    public RetrieveResult retrieveObjectList(Vsphere provider, @Nonnull String baseFolder, @Nullable List<SelectionSpec> selectionSpecsArr, @Nonnull List<PropertySpec> pSpecs) throws InternalException, CloudException {
        VsphereInventoryNavigation nav = new VsphereInventoryNavigation();
        return nav.retrieveObjectList(provider, baseFolder, selectionSpecsArr, pSpecs);
    }

    @Nonnull
    @Override
    public AffinityGroup create(@Nonnull AffinityGroupCreateOptions options) throws InternalException, CloudException {
        throw new OperationNotSupportedException("Unable to create physical hosts in vSphere");
    }

    @Override
    public void delete(@Nonnull String affinityGroupId) throws InternalException, CloudException {
        throw new OperationNotSupportedException("Unable to delete physical hosts in vSphere");
    }

    @Nonnull
    @Override
    public AffinityGroup get(@Nonnull String affinityGroupId) throws InternalException, CloudException {
        for (AffinityGroup ag : list(AffinityGroupFilterOptions.getInstance())) {
            if (ag.getAffinityGroupId().equals(affinityGroupId)) {
                return ag;
            }
        }
        return null;
    }

    @Nonnull
    @Override
    public Iterable<AffinityGroup> list(@Nonnull AffinityGroupFilterOptions options) throws InternalException, CloudException {
        APITrace.begin(provider, "Host.list");
        try {
            ProviderContext ctx = provider.getContext();
            if( ctx == null ) {
                throw new NoContextException();
            }
            Cache<AffinityGroup> cache = Cache.getInstance(provider, "affinityGroups", AffinityGroup.class, CacheLevel.REGION_ACCOUNT, new TimePeriod<Day>(1, TimePeriod.DAY));
            Collection<AffinityGroup> hostList = (Collection<AffinityGroup>)cache.get(ctx);

            if( hostList != null ) {
                return hostList;
            }
            List<AffinityGroup> hosts = new ArrayList<AffinityGroup>();

            TraversalSpec crToH = new TraversalSpec();
            crToH.setSkip(Boolean.FALSE);
            crToH.setType("ComputeResource");
            crToH.setPath("host");
            crToH.setName("crToH");

            List<SelectionSpec> selectionSpecsArr = new ArrayList<SelectionSpec>();
            selectionSpecsArr.add(crToH);

            List<PropertySpec> pSpecs = new ArrayList<PropertySpec>();
            // Create Property Spec
            PropertySpec propertySpec = new PropertySpec();
            propertySpec.setAll(Boolean.FALSE);
            propertySpec.getPathSet().add("name");
            propertySpec.getPathSet().add("overallStatus");
            propertySpec.setType("HostSystem");
            pSpecs.add(propertySpec);

            // Create Property Spec
            PropertySpec propertySpec2 = new PropertySpec();
            propertySpec2.setAll(Boolean.FALSE);
            propertySpec2.getPathSet().add("host");
            propertySpec2.setType("ClusterComputeResource");
            pSpecs.add(propertySpec2);

            RetrieveResult listobcont = retrieveObjectList(provider, "hostFolder", selectionSpecsArr, pSpecs);

            if (listobcont != null) {
                List<AffinityGroup> temp = new ArrayList<AffinityGroup>();
                Map<String, List<String>> dcToHostMap = new HashMap<String, List<String>>();

                for (ObjectContent oc : listobcont.getObjects()) {
                    ManagedObjectReference mr = oc.getObj();
                    if (mr.getType().equals("HostSystem")) {
                        String hostName = null, status = null;
                        List<DynamicProperty> dps = oc.getPropSet();
                        if (dps != null) {
                            for (DynamicProperty dp : dps) {
                                if (dp.getName().equals("name") ) {
                                    hostName = (String) dp.getVal();
                                }
                                else if (dp.getName().equals("overallStatus")) {
                                    ManagedEntityStatus mes = (ManagedEntityStatus) dp.getVal();
                                    status = mes.value();
                                }

                            }
                            AffinityGroup host = toAffinityGroup(mr.getValue(), hostName, status, "tempDC");
                            if ( host != null ) {
                                temp.add(host);
                            }
                        }
                    }
                    else {
                        List<DynamicProperty> dps = oc.getPropSet();
                        if (dps != null) {
                            for (DynamicProperty dp : dps) {
                                ArrayOfManagedObjectReference lMor = (ArrayOfManagedObjectReference) dp.getVal();
                                List<ManagedObjectReference> list = lMor.getManagedObjectReference();
                                List<String> hostIdList = new ArrayList<String>();
                                for (ManagedObjectReference mor : list) {
                                    hostIdList.add(mor.getValue());
                                }
                                dcToHostMap.put(mr.getValue(), hostIdList);
                            }
                        }
                    }
                }

                for (AffinityGroup host: temp) {
                    String id = host.getAffinityGroupId();
                    for (Map.Entry e : dcToHostMap.entrySet()) {
                        List<String> ids = (List<String>) e.getValue();
                        if (ids.contains(id)) {
                            host = AffinityGroup.getInstance(host.getAffinityGroupId(), host.getAffinityGroupName(), host.getDescription(), (String) e.getKey(), host.getCreationTimestamp());
                            if (options.matches(host)) {
                                hosts.add(host);
                            }
                            break;
                        }
                    }
                }
            }
            cache.put(ctx, hosts);
            ObjectMapper mapper = new ObjectMapper().setVisibility(JsonMethod.FIELD, JsonAutoDetect.Visibility.ANY);
            mapper.enableDefaultTypingAsProperty(ObjectMapper.DefaultTyping.OBJECT_AND_NON_CONCRETE, "type");
            try {
                mapper.writerWithDefaultPrettyPrinter().writeValue(new File("src/test/resources/HostSupport/daseinHosts.json"), hosts);
            } catch ( IOException e ) {}
            return hosts;
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public AffinityGroup modify(@Nonnull String affinityGroupId, @Nonnull AffinityGroupCreateOptions options) throws InternalException, CloudException {
        throw new OperationNotSupportedException("Unable to modify hosts in vSphere");
    }

    private AffinityGroup toAffinityGroup(@Nonnull String hostID, @Nonnull String hostName, @Nullable String status, @Nonnull String dataCenterID) {
        String agID = hostID;
        String agName = hostName;
        String agDesc = "Affinity group for "+agName;
        long created = 0;

        AffinityGroup ag = AffinityGroup.getInstance(agID, agName, agDesc, dataCenterID, created);
        ag.setTag("status", status);
        return ag;
    }
}
