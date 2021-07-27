package io.narayana;

import com.arjuna.ats.arjuna.common.Uid;
import com.arjuna.ats.arjuna.exceptions.ObjectStoreException;
import com.arjuna.ats.arjuna.objectstore.RecoveryStore;
import com.arjuna.ats.arjuna.objectstore.StoreManager;
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
    private static final String OBJECT_STORE_DIR_PARAM = "ObjectStoreEnvironmentBean.objectStoreDir";
    private static final String HORNET_OBJECT_STORE_DIR_PARAM = "HornetqJournalEnvironmentBean.objectStoreDir.storeDir";
    private static final String OBJECT_STORE_TYPE_PARAM = "ObjectStoreEnvironmentBean.objectStoreType";
    private static final String HORNETQ_OBJECT_STORE_ADAPTOR = "com.arjuna.ats.internal.arjuna.objectstore.hornetq.HornetqObjectStoreAdaptor";

    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalStateException("Expecting one argument which is a path to a directory where object store resides");
        }
        final File objectStorePath = new File(args[0]);
        if (!objectStorePath.isDirectory()) {
            throw new IllegalStateException("Provided argument of value '" + args[0] + "' is not a path to a directory");
        }

        System.setProperty(OBJECT_STORE_DIR_PARAM, objectStorePath.getAbsolutePath());
        System.setProperty(HORNET_OBJECT_STORE_DIR_PARAM, objectStorePath.getAbsolutePath());
        System.setProperty(OBJECT_STORE_TYPE_PARAM, HORNETQ_OBJECT_STORE_ADAPTOR);

        try {
            StoreManager.getTxLog(); // init log
            RecoveryStore recoveryStore = StoreManager.getRecoveryStore();
            System.out.println("Reading data:");
            System.out.println(getIds(recoveryStore, null));
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
                while (allTypesData.notempty() && !endOfList) {
                    currentTypeName = allTypesData.unpackString();
                    System.out.println("Type in progress: " + currentTypeName); // TODO: comment me out

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
