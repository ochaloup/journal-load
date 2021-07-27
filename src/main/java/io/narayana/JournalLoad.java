package io.narayana;

import com.arjuna.ats.arjuna.common.ObjectStoreEnvironmentBean;
import com.arjuna.ats.arjuna.common.Uid;
import com.arjuna.ats.arjuna.exceptions.ObjectStoreException;
import com.arjuna.ats.arjuna.objectstore.RecoveryStore;
import com.arjuna.ats.arjuna.objectstore.StoreManager;
import com.arjuna.ats.arjuna.objectstore.TxLog;
import com.arjuna.ats.arjuna.state.InputObjectState;
import com.arjuna.ats.internal.arjuna.common.UidHelper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * To load and list content of the Narayana object store journal
 */
public class JournalLoad {
    private static final String OBJECT_STORE_DIR_PARAM = ObjectStoreEnvironmentBean.class.getSimpleName() + ".objectStoreDir";
    private static final String OBJECT_STORE_TYPE_PARAM = ObjectStoreEnvironmentBean.class.getSimpleName() + ".objectStoreType";
    private static final String HORNETQ_OBJECT_STORE_ADAPTOR = com.arjuna.ats.internal.arjuna.objectstore.hornetq.HornetqObjectStoreAdaptor.class.getName();

    // HornetQ/ActiveMQ journal store java dependencies for WFLY 24
    // $JBOSS_HOME/modules/system/layers/base/org/jboss/jts/main/narayana-jts-idlj-5.12.0.Final.jar:$JBOSS_HOME/modules/system/layers/base/org/jboss/logging/main/jboss-logging-3.4.2.Final.jar:$JBOSS_HOME/modules/system/layers/base/org/apache/activemq/artemis/journal/main/artemis-journal-2.16.0.jar:$JBOSS_HOME/modules/system/layers/base/org/apache/activemq/artemis/journal/main/artemis-commons-2.16.0.jar:$JBOSS_HOME/modules/system/layers/base/org/apache/activemq/artemis/journal/main/activemq-artemis-native-1.0.2.jar:$JBOSS_HOME/modules/system/layers/base/io/netty/main/netty-all-4.1.66.Final.jar

    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalStateException("Expecting one argument which is a path to a directory where object store resides");
        }
        final File objectStore = new File(args[0]);
        if (!objectStore.isDirectory()) {
            throw new IllegalStateException("Provided argument of value '" + args[0] + "' is not a path to a directory");
        }

        System.setProperty(OBJECT_STORE_DIR_PARAM, objectStore.getAbsolutePath());
        System.setProperty(OBJECT_STORE_TYPE_PARAM, HORNETQ_OBJECT_STORE_ADAPTOR);

        try {
            StoreManager.getTxLog().start();
            RecoveryStore recoveryStore = StoreManager.getRecoveryStore();
        } finally {
            StoreManager.getTxLog().stop();
        }
    }

    /**
     * Remove any committed objects from the store.
     *
     * @param recoveryStore Instance of initialized recovery store that will be used to search in.
     * @param objectTypes The object types that should be removed.
     * @return The number of objects that were removed.
     * @throws ObjectStoreException The store implementation was unable to remove a committed object.
     */
    static int clearXids(RecoveryStore recoveryStore, String objectTypes) {
        Objects.requireNonNull(recoveryStore);
        Objects.requireNonNull(objectTypes);
        if (objectTypes.isEmpty()) {
            throw new IllegalStateException("objectType param for clearing Xids cannot be an empty string");
        }

        Collection<Uid> uids = getIds(recoveryStore, objectTypes);
        try {
            for (Uid uid : uids) {
                recoveryStore.remove_committed(uid, objectTypes);
            }
        } catch (ObjectStoreException e) {
            String errMsg = String.format("Error on removing type '%s' from recovery store '%s'. The work could be unfinished as stopped in middle of processing.",
                    objectTypes, recoveryStore);
            throw new IllegalStateException(errMsg, e);
        }

        return uids.size();
    }

    /**
     * Get a list object identifier for a given object type.
     * When object type is {@code null} then return all object types.
     *
     * @param recoveryStore instance of initialized recovery store that will be used to search in
     * @param objectType Object types to searched for. It can be a list delimited with comma ('{@code ,}'). When null then any type is returned.
     * @return all objects of the given type
     * @throws ObjectStoreException the store implementation was unable retrieve all types of objects
     */
    static Collection<Uid> getIds(RecoveryStore recoveryStore, String objectType) {
        Collection<Uid> ids = new ArrayList<>();

        List<String> splitObjectTypes = null;
        if(objectType != null) {
            splitObjectTypes = Arrays.asList(objectType.split(","));
        }

        InputObjectState allTypesData = new InputObjectState();
        boolean isAllTypes = false;
        try {
            isAllTypes = recoveryStore.allTypes(allTypesData);
        } catch (ObjectStoreException e) {
            String errMsg = String.format("Error on getting all types from recovery store '%s'", recoveryStore);
            throw new IllegalStateException(errMsg, e);
        }

        if (isAllTypes) {
            String currentTypeName;
            boolean endOfList = false;

            try {
                while (!endOfList) {
                    currentTypeName = allTypesData.unpackString();

                    if (currentTypeName.compareTo("") == 0)
                        endOfList = true;
                    else {
                        if (splitObjectTypes != null && !splitObjectTypes.contains(currentTypeName))
                            continue;

                        InputObjectState uids = new InputObjectState();
                        if (recoveryStore.allObjUids(currentTypeName, uids)) {
                            try {
                                boolean endOfUids = false;
                                while (!endOfUids) {
                                    Uid currentUid = UidHelper.unpackFrom(uids);
                                    if (currentUid.equals(Uid.nullUid())) {
                                        endOfUids = true;
                                    } else {
                                        ids.add(currentUid);
                                    }
                                }
                            } catch (IOException ioe) {
                                throw new IllegalStateException("Trouble of loading from object store for type " + currentTypeName, ioe);
                            }
                        }
                    }
                }
            } catch (ObjectStoreException | IOException outE) {
                throw new IllegalStateException("Trouble on unpacking data and reading UIDs", outE);
            }
        }
        return ids;
    }
}
