// Copyright 2018 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.firebase.firestore.local;

import static com.google.firebase.firestore.testutil.TestUtil.addedRemoteEvent;
import static com.google.firebase.firestore.testutil.TestUtil.assertSetEquals;
import static com.google.firebase.firestore.testutil.TestUtil.deleteMutation;
import static com.google.firebase.firestore.testutil.TestUtil.deletedDoc;
import static com.google.firebase.firestore.testutil.TestUtil.doc;
import static com.google.firebase.firestore.testutil.TestUtil.key;
import static com.google.firebase.firestore.testutil.TestUtil.map;
import static com.google.firebase.firestore.testutil.TestUtil.patchMutation;
import static com.google.firebase.firestore.testutil.TestUtil.query;
import static com.google.firebase.firestore.testutil.TestUtil.resumeToken;
import static com.google.firebase.firestore.testutil.TestUtil.setMutation;
import static com.google.firebase.firestore.testutil.TestUtil.updateRemoteEvent;
import static com.google.firebase.firestore.testutil.TestUtil.values;
import static com.google.firebase.firestore.testutil.TestUtil.version;
import static com.google.firebase.firestore.testutil.TestUtil.viewChanges;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.firebase.Timestamp;
import com.google.firebase.database.collection.ImmutableSortedMap;
import com.google.firebase.database.collection.ImmutableSortedSet;
import com.google.firebase.firestore.TestUtil.TestTargetMetadataProvider;
import com.google.firebase.firestore.auth.User;
import com.google.firebase.firestore.core.Query;
import com.google.firebase.firestore.model.Document;
import com.google.firebase.firestore.model.DocumentKey;
import com.google.firebase.firestore.model.MaybeDocument;
import com.google.firebase.firestore.model.NoDocument;
import com.google.firebase.firestore.model.ResourcePath;
import com.google.firebase.firestore.model.SnapshotVersion;
import com.google.firebase.firestore.model.mutation.Mutation;
import com.google.firebase.firestore.model.mutation.MutationBatch;
import com.google.firebase.firestore.model.mutation.MutationBatchResult;
import com.google.firebase.firestore.model.mutation.MutationResult;
import com.google.firebase.firestore.model.mutation.SetMutation;
import com.google.firebase.firestore.remote.RemoteEvent;
import com.google.firebase.firestore.remote.WatchChange.WatchTargetChange;
import com.google.firebase.firestore.remote.WatchChange.WatchTargetChangeType;
import com.google.firebase.firestore.remote.WatchChangeAggregator;
import com.google.firebase.firestore.remote.WatchStream;
import com.google.firebase.firestore.remote.WriteStream;
import com.google.firebase.firestore.testutil.TestUtil;
import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * These are tests for any implementation of the LocalStore.
 *
 * <p>To test a specific implementation of LocalStore:
 *
 * <ol>
 *   <li>Subclass LocalStoreTestCase
 *   <li>override {@link #getPersistence}, creating a new instance of Persistence.
 * </ol>
 */
public abstract class LocalStoreTestCase {
  private Persistence localStorePersistence;
  private LocalStore localStore;

  private List<MutationBatch> batches;
  private @Nullable ImmutableSortedMap<DocumentKey, MaybeDocument> lastChanges;
  private int lastTargetId;

  abstract Persistence getPersistence();

  abstract boolean garbageCollectorIsEager();

  @Before
  public void setUp() {
    localStorePersistence = getPersistence();
    localStore = new LocalStore(localStorePersistence, User.UNAUTHENTICATED);
    localStore.start();

    batches = new ArrayList<>();
    lastChanges = null;
    lastTargetId = 0;
  }

  @After
  public void tearDown() {
    localStorePersistence.shutdown();
  }

  private void writeMutation(Mutation mutation) {
    writeMutations(asList(mutation));
  }

  private void writeMutations(List<Mutation> mutations) {
    LocalWriteResult result = localStore.writeLocally(mutations);
    batches.add(new MutationBatch(result.getBatchId(), Timestamp.now(), mutations));
    lastChanges = result.getChanges();
  }

  private void applyRemoteEvent(RemoteEvent event) {
    lastChanges = localStore.applyRemoteEvent(event);
  }

  private void notifyLocalViewChanges(LocalViewChanges changes) {
    localStore.notifyLocalViewChanges(asList(changes));
  }

  private void acknowledgeMutation(long documentVersion) {
    MutationBatch batch = batches.remove(0);
    SnapshotVersion version = version(documentVersion);
    MutationResult mutationResult = new MutationResult(version, /*transformResults=*/ null);
    MutationBatchResult result =
        MutationBatchResult.create(
            batch, version, singletonList(mutationResult), WriteStream.EMPTY_STREAM_TOKEN);
    lastChanges = localStore.acknowledgeBatch(result);
  }

  private void rejectMutation() {
    MutationBatch batch = batches.get(0);
    batches.remove(0);
    lastChanges = localStore.rejectBatch(batch.getBatchId());
  }

  private int allocateQuery(Query query) {
    QueryData queryData = localStore.allocateQuery(query);
    lastTargetId = queryData.getTargetId();
    return queryData.getTargetId();
  }

  private void releaseQuery(Query query) {
    localStore.releaseQuery(query);
  }

  /** Asserts that the last target ID is the given number. */
  private void assertTargetId(int targetId) {
    assertEquals(targetId, lastTargetId);
  }

  /** Asserts that a the lastChanges contain the docs in the given array. */
  private void assertChanged(MaybeDocument... expected) {
    assertNotNull(lastChanges);

    List<MaybeDocument> actualList =
        Lists.newArrayList(Iterables.transform(lastChanges, Entry::getValue));
    assertEquals(asList(expected), actualList);

    lastChanges = null;
  }

  /** Asserts that the given keys were removed. */
  private void assertRemoved(String... keyPaths) {
    assertNotNull(lastChanges);

    ImmutableSortedMap<DocumentKey, MaybeDocument> actual = lastChanges;
    assertEquals(keyPaths.length, actual.size());
    int i = 0;
    for (Entry<DocumentKey, MaybeDocument> actualEntry : actual) {
      assertEquals(key(keyPaths[i++]), actualEntry.getKey());
      assertTrue(actualEntry.getValue() instanceof NoDocument);
    }
    lastChanges = null;
  }

  /** Asserts that the given local store contains the given document. */
  private void assertContains(MaybeDocument expected) {
    MaybeDocument actual = localStore.readDocument(expected.getKey());
    assertEquals(expected, actual);
  }

  /** Asserts that the given local store does not contain the given document. */
  private void assertNotContains(String keyPathString) {
    DocumentKey key = DocumentKey.fromPathString(keyPathString);
    MaybeDocument actual = localStore.readDocument(key);
    assertNull(actual);
  }

  @Test
  public void testMutationBatchKeys() {
    SetMutation set1 = setMutation("foo/bar", map("foo", "bar"));
    SetMutation set2 = setMutation("foo/baz", map("foo", "baz"));
    MutationBatch batch = new MutationBatch(1, Timestamp.now(), asList(set1, set2));
    Set<DocumentKey> keys = batch.getKeys();
    assertEquals(2, keys.size());
  }

  @Test
  public void testHandlesSetMutation() {
    writeMutation(setMutation("foo/bar", map("foo", "bar")));
    assertChanged(doc("foo/bar", 0, map("foo", "bar"), true));
    assertContains(doc("foo/bar", 0, map("foo", "bar"), true));

    acknowledgeMutation(0);
    assertChanged(doc("foo/bar", 0, map("foo", "bar"), false));
    if (garbageCollectorIsEager()) {
      // Nothing is pinning this anymore, as it has been acknowledged and there are no targets
      // active.
      assertNotContains("foo/bar");
    } else {
      assertContains(doc("foo/bar", 0, map("foo", "bar"), false));
    }
  }

  @Test
  public void testHandlesSetMutationThenDocument() {
    writeMutation(setMutation("foo/bar", map("foo", "bar")));
    assertChanged(doc("foo/bar", 0, map("foo", "bar"), true));
    assertContains(doc("foo/bar", 0, map("foo", "bar"), true));

    Query query = Query.atPath(ResourcePath.fromString("foo"));
    int targetId = allocateQuery(query);
    applyRemoteEvent(
        updateRemoteEvent(
            doc("foo/bar", 2, map("it", "changed"), true), asList(targetId), emptyList()));
    assertChanged(doc("foo/bar", 2, map("foo", "bar"), true));
    assertContains(doc("foo/bar", 2, map("foo", "bar"), true));
  }

  @Test
  public void testHandlesAckThenRejectThenRemoteEvent() {
    // Start a query that requires acks to be held.
    Query query = Query.atPath(ResourcePath.fromSegments(asList("foo")));
    int targetId = allocateQuery(query);

    writeMutation(setMutation("foo/bar", map("foo", "bar")));
    assertChanged(doc("foo/bar", 0, map("foo", "bar"), true));
    assertContains(doc("foo/bar", 0, map("foo", "bar"), true));

    // The last seen version is zero, so this ack must be held.
    acknowledgeMutation(1);
    assertChanged();
    assertContains(doc("foo/bar", 0, map("foo", "bar"), true));

    writeMutation(setMutation("bar/baz", map("bar", "baz")));
    assertChanged(doc("bar/baz", 0, map("bar", "baz"), true));
    assertContains(doc("bar/baz", 0, map("bar", "baz"), true));

    rejectMutation();
    assertRemoved("bar/baz");
    assertNotContains("bar/baz");

    applyRemoteEvent(
        addedRemoteEvent(
            doc("foo/bar", 2, map("it", "changed"), false), asList(targetId), emptyList()));
    assertChanged(doc("foo/bar", 2, map("it", "changed"), false));
    assertContains(doc("foo/bar", 2, map("it", "changed"), false));
    assertNotContains("bar/baz");
  }

  @Test
  public void testHandlesDeletedDocumentThenSetMutationThenAck() {
    Query query = query("foo");
    int targetId = allocateQuery(query);
    applyRemoteEvent(updateRemoteEvent(deletedDoc("foo/bar", 2), asList(targetId), emptyList()));
    assertRemoved("foo/bar");
    // Under eager GC, there is no longer a reference for the document, and it should be
    // deleted.
    if (garbageCollectorIsEager()) {
      assertNotContains("foo/bar");
    } else {
      assertContains(deletedDoc("foo/bar", 2));
    }

    writeMutation(setMutation("foo/bar", map("foo", "bar")));
    assertChanged(doc("foo/bar", 0, map("foo", "bar"), true));
    assertContains(doc("foo/bar", 0, map("foo", "bar"), true));

    releaseQuery(query);
    acknowledgeMutation(3);
    assertChanged(doc("foo/bar", 0, map("foo", "bar"), false));
    // It has been acknowledged, and should no longer be retained as there is no target and mutation
    if (garbageCollectorIsEager()) {
      assertNotContains("foo/bar");
    }
  }

  @Test
  public void testHandlesSetMutationThenDeletedDocument() {
    Query query = query("foo");
    int targetId = allocateQuery(query);
    writeMutation(setMutation("foo/bar", map("foo", "bar")));
    assertChanged(doc("foo/bar", 0, map("foo", "bar"), true));

    applyRemoteEvent(updateRemoteEvent(deletedDoc("foo/bar", 2), asList(targetId), emptyList()));
    assertChanged(doc("foo/bar", 0, map("foo", "bar"), true));
    assertContains(doc("foo/bar", 0, map("foo", "bar"), true));
  }

  @Test
  public void testHandlesDocumentThenSetMutationThenAckThenDocument() {
    Query query = Query.atPath(ResourcePath.fromString("foo"));
    int targetId = allocateQuery(query);
    applyRemoteEvent(
        addedRemoteEvent(
            doc("foo/bar", 2, map("it", "base"), false), asList(targetId), emptyList()));
    assertChanged(doc("foo/bar", 2, map("it", "base"), false));
    assertContains(doc("foo/bar", 2, map("it", "base"), false));

    writeMutation(setMutation("foo/bar", map("foo", "bar")));
    assertChanged(doc("foo/bar", 2, map("foo", "bar"), true));
    assertContains(doc("foo/bar", 2, map("foo", "bar"), true));

    acknowledgeMutation(3);
    // We haven't seen the remote event yet.
    assertChanged();
    assertContains(doc("foo/bar", 2, map("foo", "bar"), true));

    applyRemoteEvent(
        updateRemoteEvent(
            doc("foo/bar", 3, map("it", "changed"), false), asList(targetId), emptyList()));
    assertChanged(doc("foo/bar", 3, map("it", "changed"), false));
    assertContains(doc("foo/bar", 3, map("it", "changed"), false));
  }

  @Test
  public void testHandlesPatchWithoutPriorDocument() {
    writeMutation(patchMutation("foo/bar", map("foo", "bar")));
    assertRemoved("foo/bar");
    assertNotContains("foo/bar");

    acknowledgeMutation(1);
    assertRemoved("foo/bar");
    assertNotContains("foo/bar");
  }

  @Test
  public void testHandlesPatchMutationThenDocumentThenAck() {
    writeMutation(patchMutation("foo/bar", map("foo", "bar")));
    assertRemoved("foo/bar");
    assertNotContains("foo/bar");

    Query query = Query.atPath(ResourcePath.fromString("foo"));
    int targetId = allocateQuery(query);
    applyRemoteEvent(
        addedRemoteEvent(
            doc("foo/bar", 1, map("it", "base"), true), asList(targetId), emptyList()));
    assertChanged(doc("foo/bar", 1, map("foo", "bar", "it", "base"), true));
    assertContains(doc("foo/bar", 1, map("foo", "bar", "it", "base"), true));

    acknowledgeMutation(2);

    assertChanged();
    assertContains(doc("foo/bar", 1, map("foo", "bar", "it", "base"), true));

    applyRemoteEvent(
        updateRemoteEvent(
            doc("foo/bar", 2, map("foo", "bar", "it", "base"), false),
            asList(targetId),
            emptyList()));
    assertChanged(doc("foo/bar", 2, map("foo", "bar", "it", "base"), false));
    assertContains(doc("foo/bar", 2, map("foo", "bar", "it", "base"), false));
  }

  @Test
  public void testHandlesPatchMutationThenAckThenDocument() {
    writeMutation(patchMutation("foo/bar", map("foo", "bar")));
    assertRemoved("foo/bar");
    assertNotContains("foo/bar");

    acknowledgeMutation(1);
    assertRemoved("foo/bar");
    assertNotContains("foo/bar");

    Query query = Query.atPath(ResourcePath.fromString("foo"));
    int targetId = allocateQuery(query);
    applyRemoteEvent(
        updateRemoteEvent(
            doc("foo/bar", 1, map("it", "base"), false), asList(targetId), emptyList()));
    assertChanged(doc("foo/bar", 1, map("it", "base"), false));
    assertContains(doc("foo/bar", 1, map("it", "base"), false));
  }

  @Test
  public void testHandlesDeleteMutationThenAck() {
    writeMutation(deleteMutation("foo/bar"));
    assertRemoved("foo/bar");
    assertContains(deletedDoc("foo/bar", 0));

    acknowledgeMutation(1);
    assertRemoved("foo/bar");
    // There's no target pinning the doc, and we've ack'd the mutation.
    if (garbageCollectorIsEager()) {
      assertNotContains("foo/bar");
    } else {
      assertContains(deletedDoc("foo/bar", 0));
    }
  }

  @Test
  public void testHandlesDocumentThenDeleteMutationThenAck() {
    Query query = Query.atPath(ResourcePath.fromString("foo"));
    int targetId = allocateQuery(query);
    applyRemoteEvent(
        updateRemoteEvent(
            doc("foo/bar", 1, map("it", "base"), false), asList(targetId), emptyList()));
    assertChanged(doc("foo/bar", 1, map("it", "base"), false));
    assertContains(doc("foo/bar", 1, map("it", "base"), false));

    writeMutation(deleteMutation("foo/bar"));
    assertRemoved("foo/bar");
    assertContains(deletedDoc("foo/bar", 0));

    // Remove the target so only the mutation is pinning the document.
    releaseQuery(query);
    acknowledgeMutation(2);
    if (garbageCollectorIsEager()) {
      // Neither the target nor the mutation pin the document, it should be gone.
      assertNotContains("foo/bar");
    } else {
      assertContains(deletedDoc("foo/bar", 0));
    }
  }

  @Test
  public void testHandlesDeleteMutationThenDocumentThenAck() {
    Query query = Query.atPath(ResourcePath.fromString("foo"));
    int targetId = allocateQuery(query);
    writeMutation(deleteMutation("foo/bar"));
    assertRemoved("foo/bar");
    assertContains(deletedDoc("foo/bar", 0));

    applyRemoteEvent(
        updateRemoteEvent(
            doc("foo/bar", 1, map("it", "base"), false), asList(targetId), emptyList()));
    assertRemoved("foo/bar");
    assertContains(deletedDoc("foo/bar", 0));

    releaseQuery(query);
    acknowledgeMutation(2);
    assertRemoved("foo/bar");
    if (garbageCollectorIsEager()) {
      // The doc is not pinned in a target and we've acknowledged the mutation. It shouldn't exist
      // anymore.
      assertNotContains("foo/bar");
    } else {
      assertContains(deletedDoc("foo/bar", 0));
    }
  }

  @Test
  public void testHandlesDocumentThenDeletedDocumentThenDocument() {
    Query query = Query.atPath(ResourcePath.fromString("foo"));
    int targetId = allocateQuery(query);
    applyRemoteEvent(
        updateRemoteEvent(
            doc("foo/bar", 1, map("it", "base"), false), asList(targetId), emptyList()));
    assertChanged(doc("foo/bar", 1, map("it", "base"), false));
    assertContains(doc("foo/bar", 1, map("it", "base"), false));

    applyRemoteEvent(updateRemoteEvent(deletedDoc("foo/bar", 2), asList(targetId), emptyList()));
    assertRemoved("foo/bar");
    if (!garbageCollectorIsEager()) {
      assertContains(deletedDoc("foo/bar", 2));
    }

    applyRemoteEvent(
        updateRemoteEvent(
            doc("foo/bar", 3, map("it", "changed"), false), asList(targetId), emptyList()));
    assertChanged(doc("foo/bar", 3, map("it", "changed"), false));
    assertContains(doc("foo/bar", 3, map("it", "changed"), false));
  }

  @Test
  public void testHandlesSetMutationThenPatchMutationThenDocumentThenAckThenAck() {
    writeMutation(setMutation("foo/bar", map("foo", "old")));
    assertChanged(doc("foo/bar", 0, map("foo", "old"), true));
    assertContains(doc("foo/bar", 0, map("foo", "old"), true));

    writeMutation(patchMutation("foo/bar", map("foo", "bar")));
    assertChanged(doc("foo/bar", 0, map("foo", "bar"), true));
    assertContains(doc("foo/bar", 0, map("foo", "bar"), true));

    Query query = Query.atPath(ResourcePath.fromString("foo"));
    int targetId = allocateQuery(query);
    applyRemoteEvent(
        updateRemoteEvent(
            doc("foo/bar", 1, map("it", "base"), true), asList(targetId), emptyList()));
    assertChanged(doc("foo/bar", 1, map("foo", "bar"), true));
    assertContains(doc("foo/bar", 1, map("foo", "bar"), true));

    releaseQuery(query);
    acknowledgeMutation(2); // delete mutation
    assertChanged(doc("foo/bar", 1, map("foo", "bar"), true));
    assertContains(doc("foo/bar", 1, map("foo", "bar"), true));

    acknowledgeMutation(3); // patch mutation
    assertChanged(doc("foo/bar", 1, map("foo", "bar"), false));
    if (garbageCollectorIsEager()) {
      // we've ack'd all of the mutations, nothing is keeping this pinned anymore
      assertNotContains("foo/bar");
    } else {
      assertContains(doc("foo/bar", 1, map("foo", "bar"), false));
    }
  }

  @Test
  public void testHandlesSetMutationAndPatchMutationTogether() {
    writeMutations(
        asList(
            setMutation("foo/bar", map("foo", "old")),
            patchMutation("foo/bar", map("foo", "bar"))));

    assertChanged(doc("foo/bar", 0, map("foo", "bar"), true));
    assertContains(doc("foo/bar", 0, map("foo", "bar"), true));
  }

  @Test
  public void testHandlesSetMutationThenPatchMutationThenReject() {
    if (!garbageCollectorIsEager()) {
      return;
    }

    writeMutation(setMutation("foo/bar", map("foo", "old")));
    assertContains(doc("foo/bar", 0, map("foo", "old"), true));
    acknowledgeMutation(1);
    assertNotContains("foo/bar");

    writeMutation(patchMutation("foo/bar", map("foo", "bar")));
    // A blind patch is not visible in the cache
    assertNotContains("foo/bar");

    rejectMutation();
    assertNotContains("foo/bar");
  }

  @Test
  public void testHandlesSetMutationsAndPatchMutationOfJustOneTogether() {
    writeMutations(
        asList(
            setMutation("foo/bar", map("foo", "old")),
            setMutation("bar/baz", map("bar", "baz")),
            patchMutation("foo/bar", map("foo", "bar"))));

    assertChanged(
        doc("bar/baz", 0, map("bar", "baz"), true), doc("foo/bar", 0, map("foo", "bar"), true));

    assertContains(doc("foo/bar", 0, map("foo", "bar"), true));
    assertContains(doc("bar/baz", 0, map("bar", "baz"), true));
  }

  @Test
  public void testHandlesDeleteMutationThenPatchMutationThenAckThenAck() {
    writeMutation(deleteMutation("foo/bar"));
    assertRemoved("foo/bar");
    assertContains(deletedDoc("foo/bar", 0));

    writeMutation(patchMutation("foo/bar", map("foo", "bar")));
    assertRemoved("foo/bar");
    assertContains(deletedDoc("foo/bar", 0));

    acknowledgeMutation(2); // delete mutation
    assertRemoved("foo/bar");
    assertContains(deletedDoc("foo/bar", 0));

    acknowledgeMutation(3); // patch mutation
    assertRemoved("foo/bar");
    if (garbageCollectorIsEager()) {
      // There are no more pending mutations, the doc has been dropped
      assertNotContains("foo/bar");
    } else {
      assertContains(deletedDoc("foo/bar", 0));
    }
  }

  @Test
  public void testCollectsGarbageAfterChangeBatchWithNoTargetIDs() {
    if (!garbageCollectorIsEager()) {
      return;
    }

    int targetId = 1;
    applyRemoteEvent(
        updateRemoteEvent(deletedDoc("foo/bar", 2), emptyList(), emptyList(), asList(targetId)));
    assertNotContains("foo/bar");

    applyRemoteEvent(
        updateRemoteEvent(
            doc("foo/bar", 2, map("foo", "bar"), false),
            emptyList(),
            emptyList(),
            asList(targetId)));
    assertNotContains("foo/bar");
  }

  @Test
  public void testCollectsGarbageAfterChangeBatch() {
    if (!garbageCollectorIsEager()) {
      return;
    }

    Query query = Query.atPath(ResourcePath.fromString("foo"));
    allocateQuery(query);
    assertTargetId(2);

    List<Integer> none = asList();
    List<Integer> two = asList(2);
    applyRemoteEvent(addedRemoteEvent(doc("foo/bar", 2, map("foo", "bar"), false), two, none));
    assertContains(doc("foo/bar", 2, map("foo", "bar"), false));

    applyRemoteEvent(updateRemoteEvent(doc("foo/bar", 2, map("foo", "baz"), false), none, two));

    assertNotContains("foo/bar");
  }

  @Test
  public void testCollectsGarbageAfterAcknowledgedMutation() {
    if (!garbageCollectorIsEager()) {
      return;
    }

    Query query = Query.atPath(ResourcePath.fromString("foo"));
    int targetId = allocateQuery(query);
    applyRemoteEvent(
        updateRemoteEvent(
            doc("foo/bar", 0, map("foo", "old"), false), asList(targetId), emptyList()));
    writeMutation(patchMutation("foo/bar", map("foo", "bar")));
    releaseQuery(query);
    writeMutation(setMutation("foo/bah", map("foo", "bah")));
    writeMutation(deleteMutation("foo/baz"));
    assertContains(doc("foo/bar", 0, map("foo", "bar"), true));
    assertContains(doc("foo/bah", 0, map("foo", "bah"), true));
    assertContains(deletedDoc("foo/baz", 0));

    acknowledgeMutation(3);
    assertNotContains("foo/bar");
    assertContains(doc("foo/bah", 0, map("foo", "bah"), true));
    assertContains(deletedDoc("foo/baz", 0));

    acknowledgeMutation(4);
    assertNotContains("foo/bar");
    assertNotContains("foo/bah");
    assertContains(deletedDoc("foo/baz", 0));

    acknowledgeMutation(5);
    assertNotContains("foo/bar");
    assertNotContains("foo/bah");
    assertNotContains("foo/baz");
  }

  @Test
  public void testCollectsGarbageAfterRejectedMutation() {
    if (!garbageCollectorIsEager()) {
      return;
    }

    Query query = Query.atPath(ResourcePath.fromString("foo"));
    int targetId = allocateQuery(query);
    applyRemoteEvent(
        updateRemoteEvent(
            doc("foo/bar", 0, map("foo", "old"), false), asList(targetId), emptyList()));
    writeMutation(patchMutation("foo/bar", map("foo", "bar")));
    // Release the query so that our target count goes back to 0 and we are considered up-to-date.
    releaseQuery(query);
    writeMutation(setMutation("foo/bah", map("foo", "bah")));
    writeMutation(deleteMutation("foo/baz"));
    assertContains(doc("foo/bar", 0, map("foo", "bar"), true));
    assertContains(doc("foo/bah", 0, map("foo", "bah"), true));
    assertContains(deletedDoc("foo/baz", 0));

    rejectMutation(); // patch mutation
    assertNotContains("foo/bar");
    assertContains(doc("foo/bah", 0, map("foo", "bah"), true));
    assertContains(deletedDoc("foo/baz", 0));

    rejectMutation(); // set mutation
    assertNotContains("foo/bar");
    assertNotContains("foo/bah");
    assertContains(deletedDoc("foo/baz", 0));

    rejectMutation(); // delete mutation
    assertNotContains("foo/bar");
    assertNotContains("foo/bah");
    assertNotContains("foo/baz");
  }

  @Test
  public void testPinsDocumentsInTheLocalView() {
    if (!garbageCollectorIsEager()) {
      return;
    }

    Query query = Query.atPath(ResourcePath.fromString("foo"));
    allocateQuery(query);
    assertTargetId(2);

    List<Integer> none = asList();
    List<Integer> two = asList(2);
    applyRemoteEvent(addedRemoteEvent(doc("foo/bar", 1, map("foo", "bar"), false), two, none));
    writeMutation(setMutation("foo/baz", map("foo", "baz")));
    assertContains(doc("foo/bar", 1, map("foo", "bar"), false));
    assertContains(doc("foo/baz", 0, map("foo", "baz"), true));

    notifyLocalViewChanges(viewChanges(2, asList("foo/bar", "foo/baz"), emptyList()));
    applyRemoteEvent(updateRemoteEvent(doc("foo/bar", 1, map("foo", "bar"), false), none, two));
    applyRemoteEvent(updateRemoteEvent(doc("foo/baz", 2, map("foo", "baz"), false), two, none));
    acknowledgeMutation(2);
    assertContains(doc("foo/bar", 1, map("foo", "bar"), false));
    assertContains(doc("foo/baz", 2, map("foo", "baz"), false));

    notifyLocalViewChanges(viewChanges(2, emptyList(), asList("foo/bar", "foo/baz")));
    releaseQuery(query);

    assertNotContains("foo/bar");
    assertNotContains("foo/baz");
  }

  @Test
  public void testThrowsAwayDocumentsWithUnknownTargetIDsImmediately() {
    if (!garbageCollectorIsEager()) {
      return;
    }

    int targetID = 321;
    applyRemoteEvent(
        updateRemoteEvent(
            doc("foo/bar", 1, map(), false), emptyList(), emptyList(), asList(targetID)));

    assertNotContains("foo/bar");
  }

  @Test
  public void testCanExecuteDocumentQueries() {
    localStore.writeLocally(
        asList(
            setMutation("foo/bar", map("foo", "bar")),
            setMutation("foo/baz", map("foo", "baz")),
            setMutation("foo/bar/Foo/Bar", map("Foo", "Bar"))));
    Query query = Query.atPath(ResourcePath.fromSegments(asList("foo", "bar")));
    ImmutableSortedMap<DocumentKey, Document> docs = localStore.executeQuery(query);
    assertEquals(asList(doc("foo/bar", 0, map("foo", "bar"), true)), values(docs));
  }

  @Test
  public void testCanExecuteCollectionQueries() {
    localStore.writeLocally(
        asList(
            setMutation("fo/bar", map("fo", "bar")),
            setMutation("foo/bar", map("foo", "bar")),
            setMutation("foo/baz", map("foo", "baz")),
            setMutation("foo/bar/Foo/Bar", map("Foo", "Bar")),
            setMutation("fooo/blah", map("fooo", "blah"))));
    Query query = Query.atPath(ResourcePath.fromString("foo"));
    ImmutableSortedMap<DocumentKey, Document> docs = localStore.executeQuery(query);
    assertEquals(
        asList(
            doc("foo/bar", 0, map("foo", "bar"), true), doc("foo/baz", 0, map("foo", "baz"), true)),
        values(docs));
  }

  @Test
  public void testCanExecuteMixedCollectionQueries() {
    Query query = Query.atPath(ResourcePath.fromString("foo"));
    allocateQuery(query);
    assertTargetId(2);

    applyRemoteEvent(
        updateRemoteEvent(doc("foo/baz", 10, map("a", "b"), false), asList(2), emptyList()));
    applyRemoteEvent(
        updateRemoteEvent(doc("foo/bar", 20, map("a", "b"), false), asList(2), emptyList()));
    writeMutation(setMutation("foo/bonk", map("a", "b")));

    ImmutableSortedMap<DocumentKey, Document> docs = localStore.executeQuery(query);
    assertEquals(
        asList(
            doc("foo/bar", 20, map("a", "b"), false),
            doc("foo/baz", 10, map("a", "b"), false),
            doc("foo/bonk", 0, map("a", "b"), true)),
        values(docs));
  }

  @Test
  public void testPersistsResumeTokens() {
    // This test only works in the absence of the EagerGarbageCollector.
    if (garbageCollectorIsEager()) {
      return;
    }

    Query query = query("foo/bar");
    int targetId = allocateQuery(query);
    ByteString resumeToken = resumeToken(1000);

    QueryData queryData = TestUtil.queryData(targetId, QueryPurpose.LISTEN, "foo/bar");
    TestTargetMetadataProvider testTargetMetadataProvider = new TestTargetMetadataProvider();
    testTargetMetadataProvider.setSyncedKeys(queryData, DocumentKey.emptyKeySet());

    WatchChangeAggregator aggregator = new WatchChangeAggregator(testTargetMetadataProvider);

    WatchTargetChange watchChange =
        new WatchTargetChange(WatchTargetChangeType.Current, asList(targetId), resumeToken);
    aggregator.handleTargetChange(watchChange);
    RemoteEvent remoteEvent = aggregator.createRemoteEvent(version(1000));
    applyRemoteEvent(remoteEvent);

    // Stop listening so that the query should become inactive (but persistent)
    localStore.releaseQuery(query);

    // Should come back with the same resume token
    QueryData queryData2 = localStore.allocateQuery(query);
    assertEquals(resumeToken, queryData2.getResumeToken());
  }

  @Test
  public void testDoesNotReplaceResumeTokenWithEmptyByteString() {
    // This test only works in the absence of the EagerGarbageCollector.
    if (garbageCollectorIsEager()) {
      return;
    }

    Query query = query("foo/bar");
    int targetId = allocateQuery(query);
    ByteString resumeToken = resumeToken(1000);

    QueryData queryData = TestUtil.queryData(targetId, QueryPurpose.LISTEN, "foo/bar");
    TestTargetMetadataProvider testTargetMetadataProvider = new TestTargetMetadataProvider();
    testTargetMetadataProvider.setSyncedKeys(queryData, DocumentKey.emptyKeySet());

    WatchChangeAggregator aggregator1 = new WatchChangeAggregator(testTargetMetadataProvider);

    WatchTargetChange watchChange1 =
        new WatchTargetChange(WatchTargetChangeType.Current, asList(targetId), resumeToken);
    aggregator1.handleTargetChange(watchChange1);
    RemoteEvent remoteEvent1 = aggregator1.createRemoteEvent(version(1000));
    applyRemoteEvent(remoteEvent1);

    // New message with empty resume token should not replace the old resume token
    WatchChangeAggregator aggregator2 = new WatchChangeAggregator(testTargetMetadataProvider);
    WatchTargetChange watchChange2 =
        new WatchTargetChange(
            WatchTargetChangeType.Current, asList(targetId), WatchStream.EMPTY_RESUME_TOKEN);
    aggregator2.handleTargetChange(watchChange2);
    RemoteEvent remoteEvent2 = aggregator2.createRemoteEvent(version(2000));
    applyRemoteEvent(remoteEvent2);

    // Stop listening so that the query should become inactive (but persistent)
    localStore.releaseQuery(query);

    // Should come back with the same resume token
    QueryData queryData2 = localStore.allocateQuery(query);
    assertEquals(resumeToken, queryData2.getResumeToken());
  }

  @Test
  public void testRemoteDocumentKeysForTarget() {
    Query query = Query.atPath(ResourcePath.fromString("foo"));
    allocateQuery(query);
    assertTargetId(2);

    applyRemoteEvent(
        addedRemoteEvent(doc("foo/baz", 10, map("a", "b"), false), asList(2), emptyList()));
    applyRemoteEvent(
        addedRemoteEvent(doc("foo/bar", 20, map("a", "b"), false), asList(2), emptyList()));
    writeMutation(setMutation("foo/bonk", map("a", "b")));

    ImmutableSortedSet<DocumentKey> keys = localStore.getRemoteDocumentKeys(2);
    assertSetEquals(asList(key("foo/bar"), key("foo/baz")), keys);

    keys = localStore.getRemoteDocumentKeys(2);
    assertSetEquals(asList(key("foo/bar"), key("foo/baz")), keys);
  }
}
