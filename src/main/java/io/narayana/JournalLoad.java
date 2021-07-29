package io.narayana;

import com.arjuna.ats.arjuna.StateManager;
import com.arjuna.ats.arjuna.common.Uid;
import com.arjuna.ats.arjuna.exceptions.ObjectStoreException;
import com.arjuna.ats.arjuna.objectstore.RecoveryStore;
import com.arjuna.ats.arjuna.objectstore.StoreManager;
import com.arjuna.ats.arjuna.recovery.RecoveryManager;
import com.arjuna.ats.arjuna.state.InputObjectState;
import com.arjuna.ats.arjuna.tools.osb.mbean.ParticipantStatus;
import com.arjuna.ats.arjuna.tools.osb.mbean.UidWrapper;
import com.arjuna.ats.internal.arjuna.common.UidHelper;
import com.arjuna.ats.internal.arjuna.objectstore.hornetq.HornetqJournalEnvironmentBean;
import com.arjuna.ats.internal.jta.tools.osb.mbean.jts.RecoveredTransactionWrapper;
import com.arjuna.common.internal.util.propertyservice.BeanPopulator;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * To load and list content of the Narayana object store journal
 */
@SuppressWarnings("deprecation")
public class JournalLoad {
    private static final String STATE_MANAGER_TYPE_NAME = StateManager.class.getSimpleName();
    private static final String HORNETQ_OBJECT_STORE_ADAPTOR = com.arjuna.ats.internal.arjuna.objectstore.hornetq.HornetqObjectStoreAdaptor.class.getName();

    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalStateException("Expecting one argument which is a path to a directory where object store resides");
        }
        final File objectStorePath = new File(args[0]);
        if (!objectStorePath.isDirectory()) {
            throw new IllegalStateException("Provided argument of value '" + args[0] + "' is not a path to a directory");
        }

        com.arjuna.ats.arjuna.common.arjPropertyManager.getObjectStoreEnvironmentBean().setObjectStoreType(HORNETQ_OBJECT_STORE_ADAPTOR);
        com.arjuna.ats.arjuna.common.arjPropertyManager.getObjectStoreEnvironmentBean().setObjectStoreDir(objectStorePath.getAbsolutePath());

        // ArtemisMQ Journal setup:
        HornetqJournalEnvironmentBean hornetQEnvBean = BeanPopulator.getDefaultInstance(HornetqJournalEnvironmentBean.class);
        hornetQEnvBean.setStoreDir(objectStorePath.getAbsolutePath());
        // the compact min files says what is the minimal number of files must exist for the compaction to start
        hornetQEnvBean.setCompactMinFiles(1);
        // min files defines the number of files to be pre-create on journal startup
        hornetQEnvBean.setMinFiles(5);
        // the compact percentage defines that there has to be less than the threshold of live data for the compaction to start
        hornetQEnvBean.setCompactPercentage(50);
        // when reclaiming files it will shrink back to the journal-pool-files
        hornetQEnvBean.setPoolSize(10);

        // for some reason the jbossts-properties.xml setup does not work here properly
        // need to reset all this otherwise the journal made troubles of not stopping all journal buffer threads
        com.arjuna.ats.arjuna.common.recoveryPropertyManager.getRecoveryEnvironmentBean().setRecoveryActivatorClassNames(null);
        com.arjuna.ats.arjuna.common.recoveryPropertyManager.getRecoveryEnvironmentBean().setExpiryScannerClassNames(null);
        com.arjuna.ats.arjuna.common.recoveryPropertyManager.getRecoveryEnvironmentBean().setRecoveryModuleClassNames(null);

        // to be able to load data about participant records (see com.arjuna.ats.arjuna.coordinator.abstractrecord.RecordTypeManager)
        com.arjuna.ats.internal.jta.Implementations.initialise();
        com.arjuna.ats.internal.jts.Implementations.initialise();
        com.arjuna.ats.internal.txoj.Implementations.initialise();

        try {
            // setup recovery manager to be run only on demand and not as a background thread
            RecoveryManager.manager(RecoveryManager.DIRECT_MANAGEMENT);

            RecoveryStore recoveryStore = StoreManager.getRecoveryStore();

            if (Boolean.getBoolean("clear.xids")) {
                System.out.println("Deleted: " + clearXids(recoveryStore, null));
            } else { // just printing
                System.out.printf("Reading data from store %s:%n", objectStorePath.getAbsolutePath());
                printIds(recoveryStore, null);
            }
        } finally {
            RecoveryManager.manager().terminate();
            StoreManager.getRecoveryStore().stop();
        }
    }

    /**
     * Remove any committed objects from the store.
     *
     * @param recoveryStore Instance of initialized recovery store that will be used to search in.
     * @param objectTypes The object types that should be removed. When null then remove all types.
     * @return The number of objects that were removed.
     */
    static int clearXids(RecoveryStore recoveryStore, String objectTypes) {
        final AtomicInteger counter = new AtomicInteger();
        processIds(recoveryStore, objectTypes, uidHolder -> {
            try {
                recoveryStore.remove_committed(uidHolder.uid, uidHolder.type);
                counter.incrementAndGet();
            } catch (ObjectStoreException e) {
                String errMsg = String.format("Error on removing type '%s' from recovery store '%s'. The work could be unfinished as stopped in middle of processing.",
                        objectTypes, recoveryStore);
                throw new IllegalStateException(errMsg, e);
            }
        });
        return counter.get();
    }

    static Collection<UidDataHolder> getStoredIds(RecoveryStore recoveryStore, String objectType) {
        final Collection<UidDataHolder> uids = new ArrayList<>();
        processIds(recoveryStore, objectType, uids::add);
        return uids;
    }

    static void printIds(RecoveryStore recoveryStore, String objectType) {
        processIds(recoveryStore, objectType, holder -> {
            if (holder.recoveredTransaction != null) {
                RecoveredTransactionWrapper recTxn = holder.recoveredTransaction;
                try {
                    System.out.println("For uid: " + holder.uid + " of type " + holder.type + ", " + recTxn + ", participants:");
                    System.out.printf("  [%s:%s, %s:%s, %s:%s, %s:%s, %s:%s]%n",
                            ParticipantStatus.FAILED.name(), recTxn.getRecords(ParticipantStatus.FAILED),
                            ParticipantStatus.PREPARED.name(), recTxn.getRecords(ParticipantStatus.PREPARED),
                            ParticipantStatus.HEURISTIC.name(), recTxn.getRecords(ParticipantStatus.HEURISTIC),
                            ParticipantStatus.PENDING.name(), recTxn.getRecords(ParticipantStatus.PENDING),
                            ParticipantStatus.READONLY.name(), recTxn.getRecords(ParticipantStatus.READONLY)
                    );
                } catch (Exception ose) {
                    System.err.println("Trouble on activating AtomicAction of uid: " + holder.uid);
                    ose.printStackTrace(System.err);
                }
            } else {
                System.out.printf("%s, %s%n", holder.uid, holder.type);
            }
        });
    }

    /**
     * Get a list object identifier for a given object type.
     * When object type is {@code null} then return all object types.
     *
     * @param recoveryStore instance of initialized recovery store that will be used to search in
     * @param objectTypes Object types to searched for. It can be a list delimited with comma ('{@code ,}'). When null then any type is returned.
     */
    static void processIds(RecoveryStore recoveryStore, String objectTypes, Consumer<UidDataHolder> supplier) {
        List<String> splitObjectTypes = null;
        if(objectTypes != null) {
            splitObjectTypes = Arrays.asList(objectTypes.split(","));
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

                    if (currentTypeName.compareTo("") == 0)
                        endOfList = true;
                    else {
                        if (splitObjectTypes != null && !splitObjectTypes.contains(currentTypeName))
                            continue;

                        InputObjectState uidObjects = new InputObjectState();
                        if (recoveryStore.allObjUids(currentTypeName, uidObjects)) {
                            try {
                                while (uidObjects.notempty()) {
                                    Uid uid = UidHelper.unpackFrom(uidObjects);
                                    if (uid.equals(Uid.nullUid())) { // no more data for the type
                                        break;
                                    }
                                    if (currentTypeName.contains(STATE_MANAGER_TYPE_NAME)) {
                                        UidWrapper.setRecordWrapperTypeName(currentTypeName);
                                        supplier.accept(new UidDataHolder(currentTypeName, uid, new RecoveredTransactionWrapper(uid)));
                                        UidWrapper.setRecordWrapperTypeName(null);
                                    } else {
                                        supplier.accept(new UidDataHolder(currentTypeName, uid));
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
    }

    static final class UidDataHolder {
        final String type;
        final Uid uid;
        final RecoveredTransactionWrapper recoveredTransaction;
        UidDataHolder(String type, Uid uid, RecoveredTransactionWrapper recoveredTransaction) {
            this.type = type;
            this.uid = uid;
            this.recoveredTransaction = recoveredTransaction;
        }
        UidDataHolder(String type, Uid uid) {
            this(type, uid, null);
        }
    }
}
