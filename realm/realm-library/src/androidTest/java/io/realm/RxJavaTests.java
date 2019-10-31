/*
 * Copyright 2015 Realm Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.realm;

import android.support.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.reactivex.Flowable;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Consumer;
import io.reactivex.schedulers.Schedulers;
import io.realm.entities.AllJavaTypes;
import io.realm.entities.AllTypes;
import io.realm.entities.CyclicType;
import io.realm.entities.Dog;
import io.realm.internal.util.Pair;
import io.realm.log.RealmLog;
import io.realm.rule.RunInLooperThread;
import io.realm.rule.RunTestInLooperThread;
import io.realm.rule.TestRealmConfigurationFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * This class contains tests for the RxJava integration.
 *
 * Note that all tests must be run using @RunTestInLooperThread due to how the Observables
 * are constructed.
 */
@RunWith(AndroidJUnit4.class)
public class RxJavaTests {

    @Rule
    public final RunInLooperThread looperThread = new RunInLooperThread();

    @Rule
    public final TestRealmConfigurationFactory configFactory = new TestRealmConfigurationFactory();

    private Realm realm;
    private Disposable subscription;

    @Before
    public void setUp() {
        // For non-LooperThread tests.
        realm = looperThread.getRealm();
        looperThread.runAfterTest(() -> {
            if (subscription != null && !subscription.isDisposed()) {
                subscription.dispose();
                realm.close();
            }
        });
    }

    @Test
    @RunTestInLooperThread
    public void realmObject_emittedOnSubscribe() {
        realm.beginTransaction();
        final AllTypes obj = realm.createObject(AllTypes.class);
        realm.commitTransaction();

        subscription = obj.<AllTypes>asFlowable().subscribe(rxObject -> {
            assertTrue(rxObject.isFrozen());
            assertTrue(rxObject.equals(obj));
            looperThread.testComplete();
        });
    }

    @Test
    @RunTestInLooperThread
    public void realmObject_emitChangesetOnSubscribe() {
        realm.beginTransaction();
        final AllJavaTypes obj = realm.createObject(AllJavaTypes.class, 42);
        realm.commitTransaction();

        subscription = obj.<AllJavaTypes>asChangesetObservable().subscribe(change -> {
            assertTrue(change.getObject().isFrozen());
            assertEquals(change.getObject().getFieldId(), obj.getFieldId());
            assertNull(change.getChangeset());
            looperThread.testComplete();
        });
    }

    @Test
    @RunTestInLooperThread
    public void dynamicRealmObject_emitChangesetOnSubscribe() {
        DynamicRealm dynamicRealm = DynamicRealm.getInstance(looperThread.getConfiguration());
        dynamicRealm.beginTransaction();
        final DynamicRealmObject obj = dynamicRealm.createObject(AllJavaTypes.CLASS_NAME, 42);
        dynamicRealm.commitTransaction();

        subscription = obj.<DynamicRealmObject>asChangesetObservable().subscribe(change -> {
            assertTrue(change.getObject().isFrozen());
            assertEquals(change.getObject().getLong(AllJavaTypes.FIELD_ID), obj.getLong(AllJavaTypes.FIELD_ID));
            assertNull(change.getChangeset());
            dynamicRealm.close();
            looperThread.testComplete();
        });
    }

    @Test
    @RunTestInLooperThread
    public void realmObject_emittedOnUpdate() {
        final AtomicInteger subscriberCalled = new AtomicInteger(0);
        realm.beginTransaction();
        final AllTypes obj = realm.createObject(AllTypes.class);
        realm.commitTransaction();

        subscription = obj.<AllTypes>asFlowable().subscribe(rxObject -> {
            assertTrue(rxObject.isFrozen());
            if (rxObject.isLoaded() && rxObject.getColumnLong() == 0) {
                realm.beginTransaction();
                obj.setColumnLong(1);
                realm.commitTransaction();
            } else if (rxObject.getColumnLong() == 1) {
                looperThread.testComplete();
            }
        });
    }

    @Test
    @RunTestInLooperThread
    public void realmObject_emittedChangesetOnUpdate() {
        realm.beginTransaction();
        final AllTypes obj = realm.createObject(AllTypes.class);
        realm.commitTransaction();

        subscription = obj.<AllTypes>asChangesetObservable().subscribe(change -> {
            AllTypes rxObject = change.getObject();
            assertTrue(rxObject.isFrozen());
            if (rxObject.getColumnLong() == 0) {
                realm.beginTransaction();
                obj.setColumnLong(1);
                realm.commitTransaction();
            } else if (rxObject.getColumnLong() == 1) {
                assertNotNull(change.getChangeset());
                assertTrue(change.getChangeset().isFieldChanged(AllTypes.FIELD_LONG));
                looperThread.testComplete();
            }
        });
    }

    @Test
    @RunTestInLooperThread
    public void dynamicRealmObject_emittedChangesetOnUpdate() {
        DynamicRealm realm = DynamicRealm.getInstance(looperThread.getConfiguration());
        looperThread.closeAfterTest(realm);

        realm.beginTransaction();
        final DynamicRealmObject obj = realm.createObject(AllTypes.CLASS_NAME);
        realm.commitTransaction();

        subscription = obj.<DynamicRealmObject>asChangesetObservable().subscribe(change -> {
            DynamicRealmObject rxObject = change.getObject();
            assertTrue(rxObject.isFrozen());
            if (rxObject.getLong(AllTypes.FIELD_LONG) == 0) {
                realm.beginTransaction();
                obj.setLong(AllTypes.FIELD_LONG, 1);
                realm.commitTransaction();
            } else if (rxObject.getLong(AllTypes.FIELD_LONG) == 1) {
                assertNotNull(change.getChangeset());
                assertTrue(change.getChangeset().isFieldChanged(AllTypes.FIELD_LONG));
                looperThread.testComplete();
            }
        });
    }

    @Test
    @RunTestInLooperThread
    public void findFirst_emittedOnSubscribe() {
        realm.beginTransaction();
        realm.createObject(AllTypes.class).setColumnLong(42);
        realm.commitTransaction();

        subscription = realm.where(AllTypes.class).equalTo(AllTypes.FIELD_LONG, 42).findFirst().<AllTypes>asFlowable()
                .subscribe(rxObject -> {
                    assertTrue(rxObject.isFrozen());
                    assertEquals(42, rxObject.getColumnLong());
                    looperThread.testComplete();
                });
    }

    @Test
    @RunTestInLooperThread
    public void findFirstAsync_emittedOnSubscribe() {
        realm.beginTransaction();
        realm.createObject(AllTypes.class).setColumnLong(42);
        realm.commitTransaction();

        final AllTypes asyncObj = realm.where(AllTypes.class).findFirstAsync();
        subscription = asyncObj.<AllTypes>asFlowable().subscribe(rxObject -> {
            assertTrue(rxObject.isFrozen());
            assertEquals(42, rxObject.getColumnLong());
            looperThread.testComplete();
        });
    }

    @Test
    @RunTestInLooperThread
    public void findFirstAsync_emittedOnUpdate() {
        realm.beginTransaction();
        realm.createObject(AllTypes.class).setColumnLong(1);
        realm.commitTransaction();

        subscription = realm.where(AllTypes.class).findFirstAsync().<AllTypes>asFlowable().subscribe(rxObject -> {
            assertTrue(rxObject.isFrozen());
            if (rxObject.getColumnLong() == 1) {
                realm.executeTransaction(r -> realm.where(AllTypes.class).findFirst().setColumnLong(42));
            } else if (rxObject.getColumnLong() == 42) {
                looperThread.testComplete();
            }
        });
    }

    @Test
    @RunTestInLooperThread
    public void findFirstAsync_emittedOnDelete() {
        realm.beginTransaction();
        realm.createObject(AllTypes.class);
        realm.commitTransaction();

        subscription = realm.where(AllTypes.class).findFirstAsync().<AllTypes>asFlowable().subscribe(rxObject -> {
            assertTrue(rxObject.isFrozen());
            if (!rxObject.isLoaded()) {
                //noinspection UnnecessaryReturnStatement
                return;
            } else if (rxObject.isValid()) {
                realm.executeTransactionAsync(r -> r.delete(AllTypes.class));
            } else if (!rxObject.isValid()) {
                looperThread.testComplete();
            }
        });
    }

    @Test
    @RunTestInLooperThread
    public void realmResults_emittedOnSubscribe() {
        final RealmResults<AllTypes> results = realm.where(AllTypes.class).findAll();
        subscription = results.asFlowable().subscribe(rxResults -> {
            assertTrue(rxResults.isFrozen());
            looperThread.testComplete();
        });
    }

    @Test
    @RunTestInLooperThread
    public void realmResults_emittedChangesetOnSubscribe() {
        final RealmResults<AllTypes> results = realm.where(AllTypes.class).findAll();
        subscription = results.asChangesetObservable().subscribe(change -> {
            RealmResults<AllTypes> rxResults = change.getCollection();
            assertTrue(rxResults.isFrozen());
            assertEquals(results, rxResults);
            looperThread.testComplete();
        });
    }

    @Test
    @RunTestInLooperThread
    public void realmList_emittedOnSubscribe() {
        realm.beginTransaction();
        final RealmList<Dog> list = realm.createObject(AllTypes.class).getColumnRealmList();
        list.add(new Dog("dog"));
        realm.commitTransaction();

        subscription = list.asFlowable().subscribe(rxList -> {
            assertTrue(rxList.isFrozen());
            assertEquals(1, rxList.size());
            assertEquals("dog", rxList.first().getName());
            looperThread.testComplete();
        });
    }

    @Test
    @RunTestInLooperThread
    public void realmList_emittedChangesetOnSubscribe() {
        realm.beginTransaction();
        final RealmList<Dog> list = realm.createObject(AllTypes.class).getColumnRealmList();
        list.add(new Dog("dog"));
        realm.commitTransaction();

        subscription = list.asChangesetObservable().subscribe(change -> {
            RealmList<Dog> rxList = change.getCollection();
            assertTrue(rxList.isFrozen());
            assertEquals(1, rxList.size());
            assertEquals("dog", rxList.first().getName());
            assertNull(change.getChangeset());
            looperThread.testComplete();
        });
    }

    @Test
    @RunTestInLooperThread
    public void dynamicRealmResults_emittedOnSubscribe() {
        final DynamicRealm dynamicRealm = DynamicRealm.getInstance(looperThread.getConfiguration());
        final RealmResults<DynamicRealmObject> results = dynamicRealm.where(AllTypes.CLASS_NAME).findAll();
        subscription = results.asFlowable().subscribe(rxResults -> {
            assertTrue(rxResults.isFrozen());
            assertTrue(rxResults.equals(results));
            dynamicRealm.close();
            looperThread.testComplete();
        });
    }

    @Test
    @RunTestInLooperThread
    public void dynamicRealmResults_emittedChangesetOnSubscribe() {
        final DynamicRealm dynamicRealm = DynamicRealm.getInstance(realm.getConfiguration());
        final RealmResults<DynamicRealmObject> results = dynamicRealm.where(AllTypes.CLASS_NAME).findAll();
        subscription = results.asChangesetObservable().subscribe(change -> {
            assertTrue(change.getCollection().isFrozen());
            assertEquals(results, change.getCollection());
            assertNull(change.getChangeset());
            dynamicRealm.close();
            looperThread.testComplete();
        });
    }

    @Test
    @RunTestInLooperThread
    public void realmResults_emittedOnUpdate() {
        RealmResults<AllTypes> results = realm.where(AllTypes.class).findAll();

        subscription = results.asFlowable().subscribe(rxResults -> {
            assertTrue(rxResults.isFrozen());
            if (rxResults.size() == 1) {
                looperThread.testComplete();
            }
        });

        realm.beginTransaction();
        realm.createObject(AllTypes.class);
        realm.commitTransaction();
    }

    @Test
    @RunTestInLooperThread
    public void realmResults_emittedChangesetOnUpdate() {
        RealmResults<AllTypes> results = realm.where(AllTypes.class).findAll();

        subscription = results.asChangesetObservable().subscribe(change -> {
            RealmResults<AllTypes> rxResults = change.getCollection();
            assertTrue(rxResults.isFrozen());
            if (rxResults.isEmpty()) {
                realm.executeTransaction(r -> r.createObject(AllTypes.class));
            } else if (rxResults.size() == 1) {
                assertEquals(1, change.getChangeset().getInsertions().length);
                looperThread.testComplete();
            }
        });
    }

    @Test
    @RunTestInLooperThread
    public void realmList_emittedOnUpdate() {
        realm.beginTransaction();
        final RealmList<Dog> list = realm.createObject(AllTypes.class).getColumnRealmList();
        realm.commitTransaction();

        subscription = list.asFlowable().subscribe(rxList -> {
            assertTrue(rxList.isFrozen());
            if (rxList.isEmpty()) {
                realm.executeTransaction(r -> list.add(new Dog()));
            } else {
                assertEquals(1, list.size());
                looperThread.testComplete();
            }
        });
    }

    @Test
    @RunTestInLooperThread
    public void realmList_emittedChangesetOnUpdate() {
        realm.beginTransaction();
        final RealmList<Dog> list = realm.createObject(AllTypes.class).getColumnRealmList();
        realm.commitTransaction();

        subscription = list.asChangesetObservable().subscribe(change -> {
            RealmList<Dog> rxList = change.getCollection();
            assertTrue(rxList.isFrozen());
            if (rxList.isLoaded() && rxList.size() == 0) {
                realm.beginTransaction();
                list.add(new Dog());
                realm.commitTransaction();
            } else if (rxList.size() == 1) {
                assertEquals(1, change.getChangeset().getInsertions().length);
                looperThread.testComplete();
            }
        });
    }

    @Test
    @RunTestInLooperThread
    public void dynamicRealmResults_emittedOnUpdate() {
        final DynamicRealm dynamicRealm = DynamicRealm.getInstance(looperThread.getConfiguration());
        RealmResults<DynamicRealmObject> results = dynamicRealm.where(AllTypes.CLASS_NAME).findAll();

        subscription = results.asFlowable().subscribe(rxResults -> {
            assertTrue(rxResults.isFrozen());
            if (rxResults.isLoaded() && rxResults.size() == 1) {
                dynamicRealm.close();
                looperThread.testComplete();
            }
        });

        dynamicRealm.beginTransaction();
        dynamicRealm.createObject(AllTypes.CLASS_NAME);
        dynamicRealm.commitTransaction();
    }

    @Test
    @RunTestInLooperThread
    public void dynamicRealmResults_emittedChangesetOnUpdate() {
        final DynamicRealm dynamicRealm = DynamicRealm.getInstance(looperThread.getConfiguration());
        looperThread.closeAfterTest(dynamicRealm);
        RealmResults<DynamicRealmObject> results = dynamicRealm.where(AllTypes.CLASS_NAME).findAll();

        subscription = results.asChangesetObservable().subscribe(change -> {
            RealmResults<DynamicRealmObject> collection = change.getCollection();
            if (collection.isLoaded() && collection.isEmpty()) {
                looperThread.postRunnable(() -> {
                    DynamicRealm dynRealm = DynamicRealm.getInstance(looperThread.getConfiguration());
                    dynRealm.executeTransaction(dr -> {
                        dr.createObject(AllTypes.CLASS_NAME);
                    });
                });
            }

            if (collection.isLoaded() && collection.size() == 1) {
                assertEquals(1, change.getChangeset().getInsertions().length);
                looperThread.testComplete();
            }
        });
    }

    @Test
    @RunTestInLooperThread
    public void findAllAsync_emittedOnSubscribe() {
        Realm realm = looperThread.getRealm();
        final RealmResults<AllTypes> results = realm.where(AllTypes.class).findAllAsync();
        subscription = results.asFlowable().subscribe(rxResults -> {
            assertTrue(rxResults.isFrozen());
            looperThread.testComplete();
        });
    }

    @Test
    @RunTestInLooperThread
    public void findAllAsync_emittedOnUpdate() {
        final AtomicInteger subscriberCalled = new AtomicInteger(0);
        Realm realm = looperThread.getRealm();
        subscription = realm.where(AllTypes.class).findAllAsync().asFlowable().subscribe(rxResults -> {
            assertTrue(rxResults.isFrozen());
            if (subscriberCalled.incrementAndGet() == 2) {
                looperThread.testComplete();
            }
        });

        realm.beginTransaction();
        realm.createObject(AllTypes.class);
        realm.commitTransaction();
    }

    @Test
    @RunTestInLooperThread
    public void realm_emittedOnSubscribe() {
        subscription = realm.asFlowable().subscribe(rxRealm -> {
            assertTrue(rxRealm.isFrozen());
            assertEquals(realm.getPath(), rxRealm.getPath());
            looperThread.testComplete();
        });
    }

    @Test
    @RunTestInLooperThread
    public void realm_emittedOnUpdate() {
        subscription = realm.asFlowable().subscribe(rxRealm -> {
            assertTrue(rxRealm.isFrozen());
            if (rxRealm.isEmpty()) {
                realm.executeTransaction(r -> r.createObject(AllTypes.class));
            } else {
                assertEquals(1, realm.where(AllTypes.class).count());
                looperThread.testComplete();
            }
        });
    }

    @Test
    @RunTestInLooperThread
    @SuppressWarnings("ReferenceEquality")
    public void dynamicRealm_emittedOnSubscribe() {
        final DynamicRealm dynamicRealm = DynamicRealm.getInstance(realm.getConfiguration());
        looperThread.closeAfterTest(dynamicRealm);
        subscription = dynamicRealm.asFlowable().subscribe(rxRealm -> {
            assertTrue(rxRealm.isFrozen());
            assertEquals(rxRealm.getPath(), dynamicRealm.getPath());
            assertEquals(rxRealm.sharedRealm.getVersionID(), dynamicRealm.sharedRealm.getVersionID());
            looperThread.testComplete();
        });
    }

    @Test
    @RunTestInLooperThread
    public void dynamicRealm_emittedOnUpdate() {
        final DynamicRealm dynamicRealm = DynamicRealm.getInstance(looperThread.getConfiguration());
        subscription = dynamicRealm.asFlowable().subscribe(rxRealm -> {
            assertTrue(rxRealm.isFrozen());
            if (rxRealm.isEmpty()) {
                dynamicRealm.executeTransaction(r -> r.createObject("AllTypes"));
            } else {
                assertEquals(1, rxRealm.where(AllTypes.CLASS_NAME).count());
                looperThread.testComplete();
            }
        });
    }

    @Test
    @RunTestInLooperThread
    @SuppressWarnings("ReferenceEquality")
    public void unsubscribe_sameThread() {
        Realm realm = looperThread.getRealm();
        subscription = realm.asFlowable()
                .doOnCancel(() -> {
                    looperThread.testComplete();
                })
                .subscribe(rxRealm -> {
            assertTrue(rxRealm.isFrozen());
            assertEquals(rxRealm.getPath(), realm.getPath());
            assertEquals(rxRealm.sharedRealm.getVersionID(), realm.sharedRealm.getVersionID());
        });
        subscription.dispose();
    }

    @Test
    @RunTestInLooperThread
    @SuppressWarnings("ReferenceEquality")
    public void unsubscribe_fromOtherThread() {
        subscription = realm.asFlowable()
            .doFinally(() -> {
                looperThread.testComplete();
            })
            .subscribe(new Consumer<Realm>() {
                @Override
                public void accept(Realm rxRealm) {
                    assertTrue(rxRealm.isFrozen());
                    assertEquals(rxRealm.getPath(), realm.getPath());
                    assertEquals(rxRealm.sharedRealm.getVersionID(), realm.sharedRealm.getVersionID());
                    looperThread.postRunnable(() -> {
                        Thread t = new Thread(() -> subscription.dispose());
                        t.start();
                        looperThread.keepStrongReference(t);
                    });
                }
            });
    }

    @Test
    @RunTestInLooperThread
    public void wrongGenericClassThrows() {
        realm.beginTransaction();
        final AllTypes obj = realm.createObject(AllTypes.class);
        realm.commitTransaction();

        Flowable<CyclicType> obs = obj.asFlowable();
        subscription = obs.subscribe(
                cyclicType -> fail(),
                ignoredError -> {
                    looperThread.testComplete();
                }
        );
    }

    @Test
    @RunTestInLooperThread
    public void realm_closeInDoOnUnsubscribe() {
        Flowable<Realm> observable = realm.asFlowable()
                .doOnCancel(() -> realm.close())
                .doFinally(() -> {
                    looperThread.postRunnable(() -> {
                        assertTrue(realm.isClosed());
                        looperThread.testComplete();
                    });
                });

        subscription = observable.subscribe(ignore -> {
            assertEquals(3, Realm.getLocalInstanceCount(realm.getConfiguration()));
            subscription.dispose();
        });
    }

    @Test
    @RunTestInLooperThread
    public void dynamicRealm_closeInDoOnUnsubscribe() {
        final DynamicRealm dynamicRealm = DynamicRealm.getInstance(realm.getConfiguration());

        Flowable<DynamicRealm> observable = dynamicRealm.asFlowable()
                .doOnCancel(() -> {
                    dynamicRealm.close();
                })
                .doFinally(() -> {
                    assertFalse(dynamicRealm.isClosed());
                    looperThread.postRunnable(() -> {
                        assertTrue(dynamicRealm.isClosed());
                        looperThread.testComplete();
                    });
                });

        subscription = observable.subscribe(ignored -> {
            subscription.dispose();
        });
    }

    @Test
    @RunTestInLooperThread
    public void realmResults_closeInDoOnUnsubscribe() {
        Realm realm = looperThread.getRealm();
        Flowable<RealmResults<AllTypes>> observable = realm.where(AllTypes.class).findAll().asFlowable()
                .doOnCancel(() -> realm.close());

        subscription = observable.subscribe(ignored -> {});
        subscription.dispose();
        assertTrue(realm.isClosed());
        looperThread.testComplete();
    }

    @Test
    @RunTestInLooperThread
    public void realmList_closeInDoOnUnsubscribe() {
        realm.beginTransaction();
        RealmList<Dog> list = realm.createObject(AllTypes.class).getColumnRealmList();
        realm.commitTransaction();

        Flowable<RealmList<Dog>> observable = list.asFlowable()
                .doOnCancel(() -> realm.close())
                .doFinally(() -> {
                    looperThread.postRunnable(() -> {
                        assertTrue(realm.isClosed());
                        looperThread.testComplete();
                    });
                });

        subscription = observable.subscribe(ignored -> {
            subscription.dispose();
        });
    }

    @Test
    @RunTestInLooperThread
    public void dynamicRealmResults_closeInDoOnUnsubscribe() {
        final DynamicRealm dynamicRealm = DynamicRealm.getInstance(looperThread.getConfiguration());

        Flowable<RealmResults<DynamicRealmObject>> flowable = dynamicRealm.where(AllTypes.CLASS_NAME).findAll().asFlowable()
                .doOnCancel(() -> {
                    dynamicRealm.close();
                })
                .doFinally(() -> {
                    looperThread.postRunnable(() -> {
                        assertTrue(dynamicRealm.isClosed());
                        looperThread.testComplete();
                    });
                });

        subscription = flowable.subscribe(ignored -> {
            subscription.dispose();
        });
    }

    @Test
    @RunTestInLooperThread
    public void realmObject_closeInDoOnUnsubscribe() {
        realm.beginTransaction();
        realm.createObject(AllTypes.class);
        realm.commitTransaction();

        Flowable<AllTypes> flowable = realm.where(AllTypes.class).findFirst().<AllTypes>asFlowable()
                .doOnCancel(() -> realm.close())
                .doFinally(() -> {
                    looperThread.postRunnable(() -> {
                        assertTrue(realm.isClosed());
                        looperThread.testComplete();
                    });
                });

        subscription = flowable.subscribe(ignored -> {
            subscription.dispose();
        });
    }

    @Test
    @RunTestInLooperThread
    public void dynamicRealmObject_closeInDoOnUnsubscribe() {
        realm.beginTransaction();
        realm.createObject(AllTypes.class);
        realm.commitTransaction();

        final DynamicRealm dynamicRealm = DynamicRealm.getInstance(looperThread.getConfiguration());

        subscription = dynamicRealm.where(AllTypes.CLASS_NAME).findFirst().<DynamicRealmObject>asFlowable()
                .doOnCancel(() -> dynamicRealm.close())
                .doFinally(() -> {
                    looperThread.postRunnable(() -> {
                        assertTrue(dynamicRealm.isClosed());
                        looperThread.testComplete();
                    });
                }).subscribe(ignored -> subscription.dispose());
    }

    // Tests that Observables keep strong references to their parent, so they are not accidentally GC'ed while
    // waiting for results from the async API's.
    @Test
    @RunTestInLooperThread
    @SuppressWarnings("CheckReturnValue")
    public void realmResults_gcStressTest() {
        final int TEST_SIZE = 50;
        final AtomicLong innerCounter = new AtomicLong();
        final Realm realm = looperThread.getRealm();

        realm.beginTransaction();
        for (int i = 0; i < TEST_SIZE; i++) {
            realm.createObject(AllTypes.class).setColumnLong(i);
        }
        realm.commitTransaction();

        for (int i = 0; i < TEST_SIZE; i++) {
            realm.where(AllTypes.class).equalTo(AllTypes.FIELD_LONG, i).findAllAsync().asFlowable()
                    .filter(results -> results.isLoaded())
                    .take(1) // Unsubscribes from Realm.
                    .subscribe(allTypes -> {
                        // Not guaranteed, but can result in the GC of other RealmResults waiting for a result.
                        Runtime.getRuntime().gc();
                        if (innerCounter.incrementAndGet() == TEST_SIZE) {
                            looperThread.testComplete();
                        }
                    }, throwable -> fail(throwable.toString()));
        }
    }

    // Tests that Observables keep strong references to their parent, so they are not accidentally GC'ed while
    // waiting for results from the async API's.
    @Test
    @RunTestInLooperThread
    @SuppressWarnings("CheckReturnValue")
    public void dynamicRealmResults_gcStressTest() {
        final int TEST_SIZE = 50;
        final AtomicLong innerCounter = new AtomicLong();
        final DynamicRealm realm = DynamicRealm.getInstance(looperThread.getConfiguration());
        looperThread.closeAfterTest(realm);

        realm.beginTransaction();
        for (int i = 0; i < TEST_SIZE; i++) {
            realm.createObject(AllTypes.CLASS_NAME).set(AllTypes.FIELD_LONG, i);
        }
        realm.commitTransaction();

        for (int i = 0; i < TEST_SIZE; i++) {
            // Doesn't keep a reference to the Observable.
            realm.where(AllTypes.CLASS_NAME).equalTo(AllTypes.FIELD_LONG, i).findAllAsync().asFlowable()
                    .filter(results -> results.isLoaded())
                    .take(1) // Unsubscribes from Realm.
                    .subscribe(dynamicRealmObjects -> {
                        // Not guaranteed, but can result in the GC of other RealmResults waiting for a result.
                        Runtime.getRuntime().gc();
                        if (innerCounter.incrementAndGet() == TEST_SIZE) {
                            looperThread.testComplete();
                        }
                    }, throwable -> fail(throwable.toString()));
        }
    }

    // Tests that Observables keep strong references to their parent, so they are not accidentally GC'ed while
    // waiting for results from the async API's.
    @Test
    @RunTestInLooperThread
    @SuppressWarnings("CheckReturnValue")
    public void realmObject_gcStressTest() {
        final int TEST_SIZE = 50;
        final AtomicLong innerCounter = new AtomicLong();
        final Realm realm = looperThread.getRealm();

        realm.beginTransaction();
        for (int i = 0; i < TEST_SIZE; i++) {
            realm.createObject(AllTypes.class).setColumnLong(i);
        }
        realm.commitTransaction();

        for (int i = 0; i < TEST_SIZE; i++) {
            // Doesn't keep a reference to the Observable.
            realm.where(AllTypes.class).equalTo(AllTypes.FIELD_LONG, i).findFirstAsync().<AllTypes>asFlowable()
                    .filter(obj -> obj.isLoaded())
                    .take(1) // Unsubscribes from Realm.
                    .subscribe(allTypes -> {
                        // Not guaranteed, but can result in the GC of other RealmResults waiting for a result.
                        Runtime.getRuntime().gc();
                        if (innerCounter.incrementAndGet() == TEST_SIZE) {
                            looperThread.testComplete();
                        }
                    }, throwable -> fail(throwable.toString()));
        }
    }

    // Tests that Observables keep strong references to their parent, so they are not accidentally GC'ed while
    // waiting for results from the async API's.
    @Test
    @RunTestInLooperThread
    @SuppressWarnings("CheckReturnValue")
    public void dynamicRealmObject_gcStressTest() {
        final int TEST_SIZE = 50;
        final AtomicLong innerCounter = new AtomicLong();
        final DynamicRealm realm = DynamicRealm.getInstance(looperThread.getConfiguration());
        looperThread.closeAfterTest(realm);

        realm.beginTransaction();
        for (int i = 0; i < TEST_SIZE; i++) {
            realm.createObject(AllTypes.CLASS_NAME).set(AllTypes.FIELD_LONG, i);
        }
        realm.commitTransaction();

        for (int i = 0; i < TEST_SIZE; i++) {
            // Doesn't keep a reference to the Observable.
            realm.where(AllTypes.CLASS_NAME).equalTo(AllTypes.FIELD_LONG, i).findFirstAsync().<DynamicRealmObject>asFlowable()
                    .filter(obj -> obj.isLoaded())
                    .take(1) // Unsubscribes from Realm.
                    .subscribe(dynamicRealmObject -> {
                        // Not guaranteed, but can result in the GC of other RealmResults waiting for a result.
                        Runtime.getRuntime().gc();
                        if (innerCounter.incrementAndGet() == TEST_SIZE) {
                            looperThread.testComplete();
                        }
                    }, throwable -> fail(throwable.toString()));
        }
    }


    @Test
    @RunTestInLooperThread
    public void asFlowable_frozenRealm() {
        subscription = realm.freeze().asFlowable()
                .subscribe(rxRealm -> {
                    assertEquals(realm, rxRealm);
                    looperThread.testComplete();
                });
    }

    @Test
    @RunTestInLooperThread
    public void asFlowable_frozenDynamicRealm() {
        DynamicRealm dynRealm = DynamicRealm.getInstance(looperThread.getConfiguration());
        subscription = dynRealm.freeze().asFlowable()
                .subscribe(rxRealm -> {
                    assertEquals(dynRealm, rxRealm);
                    dynRealm.close();
                    looperThread.testComplete();
                });
    }

    @Test
    @RunTestInLooperThread
    public void asFlowable_frozenRealmResults() {
        final RealmResults<AllTypes> results = realm.where(AllTypes.class).findAll().freeze();
        subscription = results.asFlowable().subscribe(rxResults -> {
            assertTrue(rxResults.isFrozen());
            assertEquals(results, rxResults);
            looperThread.testComplete();
        });
    }

    @Test
    @RunTestInLooperThread
    public void asChangesetObservable_frozenRealmResults() {
        final RealmResults<AllTypes> results = realm.where(AllTypes.class).findAll().freeze();
        subscription = results.asChangesetObservable().subscribe(change -> {
            RealmResults<AllTypes> rxResults = change.getCollection();
            assertTrue(rxResults.isFrozen());
            assertEquals(results, rxResults);
            assertNull(change.getChangeset());
            looperThread.testComplete();
        });
    }

    @Test
    @RunTestInLooperThread
    public void asFlowable_frozenDynamicRealmResults() {
        DynamicRealm dynRealm = DynamicRealm.getInstance(looperThread.getConfiguration());
        final RealmResults<DynamicRealmObject> results = dynRealm.where(AllTypes.CLASS_NAME).findAll().freeze();
        subscription = results.asFlowable().subscribe(rxResults -> {
            assertTrue(rxResults.isFrozen());
            assertEquals(results, rxResults);
            looperThread.testComplete();
        });
    }

    @Test
    @RunTestInLooperThread
    public void asChangesetObservable_frozenDynamicRealmResults() {
        DynamicRealm dynRealm = DynamicRealm.getInstance(looperThread.getConfiguration());
        final RealmResults<DynamicRealmObject> results = dynRealm.where(AllTypes.CLASS_NAME).findAll().freeze();
        subscription = results.asChangesetObservable().subscribe(change -> {
            RealmResults<DynamicRealmObject> rxResults = change.getCollection();
            assertTrue(rxResults.isFrozen());
            assertEquals(results, rxResults);
            assertNull(change.getChangeset());
            looperThread.testComplete();
        });
    }

    @Test
    @RunTestInLooperThread
    public void asFlowable_frozenRealmList() {
        realm.beginTransaction();
        final RealmList<Dog> list = realm.createObject(AllTypes.class).getColumnRealmList();
        list.add(new Dog("dog"));
        realm.commitTransaction();

        subscription = list.freeze().asFlowable().subscribe(rxList -> {
            assertTrue(rxList.isFrozen());
            assertEquals(list, rxList);
            looperThread.testComplete();
        });
    }

    @Test
    @RunTestInLooperThread
    public void asChangesetObservable_frozenRealmList() {
        realm.beginTransaction();
        final RealmList<Dog> list = realm.createObject(AllTypes.class).getColumnRealmList();
        list.add(new Dog("dog"));
        realm.commitTransaction();

        subscription = list.freeze().asChangesetObservable().subscribe(change -> {
            RealmList<Dog> rxList = change.getCollection();
            assertTrue(rxList.isFrozen());
            assertEquals(list, rxList);
            assertNull(change.getChangeset());
            looperThread.testComplete();
        });
    }

    @Test
    @RunTestInLooperThread
    public void asFlowable_frozenDynamicRealmList() {
        realm.beginTransaction();
        final RealmList<Dog> list = realm.createObject(AllTypes.class).getColumnRealmList();
        list.add(new Dog("dog"));
        realm.commitTransaction();

        DynamicRealm dynRealm = DynamicRealm.getInstance(looperThread.getConfiguration());
        RealmList<DynamicRealmObject> dynList = dynRealm.where(AllTypes.CLASS_NAME).findFirst().getList(AllTypes.FIELD_REALMLIST);

        subscription = dynList.freeze().asFlowable().subscribe(rxList -> {
            assertTrue(rxList.isFrozen());
            assertEquals(dynList, rxList);
            dynRealm.close();
            looperThread.testComplete();
        });
    }

    @Test
    @RunTestInLooperThread
    public void asChangesetObservable_frozenDynamicRealmList() {
        realm.beginTransaction();
        final RealmList<Dog> list = realm.createObject(AllTypes.class).getColumnRealmList();
        list.add(new Dog("dog"));
        realm.commitTransaction();

        DynamicRealm dynRealm = DynamicRealm.getInstance(looperThread.getConfiguration());
        RealmList<DynamicRealmObject> dynList = dynRealm.where(AllTypes.CLASS_NAME).findFirst().getList(AllTypes.FIELD_REALMLIST);

        subscription = dynList.freeze().asChangesetObservable().subscribe(change -> {
            RealmList<DynamicRealmObject> rxList = change.getCollection();
            assertTrue(rxList.isFrozen());
            assertEquals(dynList, rxList);
            assertNull(change.getCollection());
            dynRealm.close();
            looperThread.testComplete();
        });
    }

    @Test
    @RunTestInLooperThread
    public void asFlowable_frozenRealmObject() {
        realm.beginTransaction();
        final AllTypes obj = realm.createObject(AllTypes.class);
        realm.commitTransaction();

        subscription = obj.<AllTypes>freeze().asFlowable().subscribe(rxObject -> {
            assertTrue(rxObject.isFrozen());
            assertEquals(rxObject, obj);
            looperThread.testComplete();
        });
    }

    @Test
    @RunTestInLooperThread
    public void asChangesetObservable_frozenRealmObject() {
        realm.beginTransaction();
        final AllTypes obj = realm.createObject(AllTypes.class);
        realm.commitTransaction();

        subscription = obj.<AllTypes>freeze().<AllTypes>asChangesetObservable().subscribe(change -> {
            AllTypes rxObject = change.getObject();
            assertTrue(rxObject.isFrozen());
            assertEquals(rxObject, obj);
            looperThread.testComplete();
        });
    }

    @Test
    @RunTestInLooperThread
    public void asFlowable_frozenDynamicRealmObject() {
        realm.beginTransaction();
        realm.createObject(AllTypes.class);
        realm.commitTransaction();

        DynamicRealm dynRealm = DynamicRealm.getInstance(looperThread.getConfiguration());
        DynamicRealmObject obj = dynRealm.where(AllTypes.CLASS_NAME).findFirst();

        subscription = obj.<AllTypes>freeze().asFlowable().subscribe(rxObject -> {
            assertTrue(rxObject.isFrozen());
            assertEquals(rxObject, obj);
            looperThread.testComplete();
        });
    }

    @Test
    @RunTestInLooperThread
    public void asChangesetObservable_frozenDynamicRealmObject() {
        realm.beginTransaction();
        realm.createObject(AllTypes.class);
        realm.commitTransaction();

        DynamicRealm dynRealm = DynamicRealm.getInstance(looperThread.getConfiguration());
        DynamicRealmObject obj = dynRealm.where(AllTypes.CLASS_NAME).findFirst();

        subscription = obj.<AllTypes>freeze().asChangesetObservable().subscribe(change -> {
            RealmObject rxObject = change.getObject();
            assertTrue(rxObject.isFrozen());
            assertEquals(rxObject, obj);
            assertNull(change.getChangeset());
            looperThread.testComplete();
        });
    }

    @Test
    @RunTestInLooperThread
    public void realmResults_readableAcrossThreads() {
        // FIXME: Make a test like this for all from methods
        final long TEST_SIZE = 10;
        Realm realm = looperThread.getRealm();

        realm.beginTransaction();
        for (int i = 0; i < TEST_SIZE; i++) {
            realm.createObject(AllTypes.class).setColumnLong(1);
        }
        realm.commitTransaction();

        subscription = realm.where(AllTypes.class).sort(AllTypes.FIELD_LONG).findAllAsync().asFlowable()
                .subscribeOn(Schedulers.io())
                .filter(RealmResults::isLoaded)
                .map(results -> {
                    RealmLog.error(Thread.currentThread().getName());
                    return new Pair<>(results.size(), results);
                })
                .observeOn(Schedulers.computation())
                .subscribe(
                        pair -> {
                            RealmLog.error(Thread.currentThread().getName());
                            assertEquals(TEST_SIZE, pair.first.intValue());
                            assertEquals(TEST_SIZE, pair.second.size());
                            looperThread.testComplete();
                        }
                );
    }
}
