package org.apache.lucene.index;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.Closeable;
import java.io.IOException;
import java.util.Iterator;
import java.util.Random;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.MockAnalyzer;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.FieldTypes;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ReferenceManager;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.InfoStream;
import org.apache.lucene.util.LuceneTestCase;
import org.apache.lucene.util.NullInfoStream;
import org.apache.lucene.util.TestUtil;

/** Silly class that randomizes the indexing experience.  EG
 *  it may swap in a different merge policy/scheduler; may
 *  commit periodically; may or may not forceMerge in the end,
 *  may flush by doc count instead of RAM, etc. 
 */

public class RandomIndexWriter implements Closeable {

  public IndexWriter w;
  private final Random r;
  int docCount;
  int flushAt;
  private double flushAtFactor = 1.0;
  private boolean getReaderCalled;
  private final Codec codec; // sugar

  
  public static IndexWriter mockIndexWriter(Directory dir, IndexWriterConfig conf, Random r) throws IOException {
    // Randomly calls Thread.yield so we mixup thread scheduling
    final Random random = new Random(r.nextLong());
    return mockIndexWriter(dir, conf, new TestPoint() {
      @Override
      public void apply(String message) {
        if (random.nextInt(4) == 2)
          Thread.yield();
      }
    });
  }
  
  public static IndexWriter mockIndexWriter(Directory dir, IndexWriterConfig conf, TestPoint testPoint) throws IOException {
    conf.setInfoStream(new TestPointInfoStream(conf.getInfoStream(), testPoint));
    return new IndexWriter(dir, conf);
  }

  /** create a RandomIndexWriter with a random config: Uses MockAnalyzer */
  public RandomIndexWriter(Random r, Directory dir) throws IOException {
    this(r, dir, LuceneTestCase.newIndexWriterConfig(r, new MockAnalyzer(r)));
  }
  
  /** create a RandomIndexWriter with a random config */
  public RandomIndexWriter(Random r, Directory dir, Analyzer a) throws IOException {
    this(r, dir, LuceneTestCase.newIndexWriterConfig(r, a));
  }
  
  /** create a RandomIndexWriter with the provided config */
  public RandomIndexWriter(Random r, Directory dir, IndexWriterConfig c) throws IOException {
    // TODO: this should be solved in a different way; Random should not be shared (!).
    this.r = new Random(r.nextLong());
    w = mockIndexWriter(dir, c, r);
    flushAt = TestUtil.nextInt(r, 10, 1000);
    codec = w.getConfig().getCodec();
    if (LuceneTestCase.VERBOSE) {
      System.out.println("RIW dir=" + dir + " config=" + w.getConfig());
      System.out.println("codec default=" + codec.getName());
    }

    // Make sure we sometimes test indices that don't get
    // any forced merges:
    doRandomForceMerge = !(c.getMergePolicy() instanceof NoMergePolicy) && r.nextBoolean();
  } 

  public FieldTypes getFieldTypes() {
    return w.getFieldTypes();
  }

  public Document newDocument() {
    return w.newDocument();
  }

  /**
   * Adds a Document.
   * @see IndexWriter#addDocument(org.apache.lucene.index.IndexDocument)
   */
  public <T extends IndexableField> void addDocument(final Iterable<T> doc) throws IOException {
    LuceneTestCase.maybeChangeLiveIndexWriterConfig(r, w.getConfig());
    if (r.nextInt(5) == 3) {
      // TODO: maybe, we should simply buffer up added docs
      // (but we need to clone them), and only when
      // getReader, commit, etc. are called, we do an
      // addDocuments?  Would be better testing.
      w.addDocuments(new Iterable<Iterable<T>>() {

        @Override
        public Iterator<Iterable<T>> iterator() {
          return new Iterator<Iterable<T>>() {
            boolean done;
            
            @Override
            public boolean hasNext() {
              return !done;
            }

            @Override
            public void remove() {
              throw new UnsupportedOperationException();
            }

            @Override
            public Iterable<T> next() {
              if (done) {
                throw new IllegalStateException();
              }
              done = true;
              return doc;
            }
          };
        }
        });
    } else {
      w.addDocument(doc);
    }
    
    maybeCommit();
  }

  private void maybeCommit() throws IOException {
    LuceneTestCase.maybeChangeLiveIndexWriterConfig(r, w.getConfig());
    if (docCount++ == flushAt) {
      if (LuceneTestCase.VERBOSE) {
        System.out.println("RIW.add/updateDocument: now doing a commit at docCount=" + docCount);
      }
      w.commit();
      flushAt += TestUtil.nextInt(r, (int) (flushAtFactor * 10), (int) (flushAtFactor * 1000));
      if (flushAtFactor < 2e6) {
        // gradually but exponentially increase time b/w flushes
        flushAtFactor *= 1.05;
      }
    }
  }
  
  public void addDocuments(Iterable<? extends Iterable<? extends IndexableField>> docs) throws IOException {
    LuceneTestCase.maybeChangeLiveIndexWriterConfig(r, w.getConfig());
    w.addDocuments(docs);
    maybeCommit();
  }

  public void updateDocuments(Term delTerm, Iterable<? extends Iterable<? extends IndexableField>> docs) throws IOException {
    LuceneTestCase.maybeChangeLiveIndexWriterConfig(r, w.getConfig());
    w.updateDocuments(delTerm, docs);
    maybeCommit();
  }

  /**
   * Updates a document.
   * @see IndexWriter#updateDocument(Term, org.apache.lucene.index.IndexDocument)
   */
  public <T extends IndexableField> void updateDocument(Term t, final Iterable<T> doc) throws IOException {
    LuceneTestCase.maybeChangeLiveIndexWriterConfig(r, w.getConfig());
    if (r.nextInt(5) == 3) {
      w.updateDocuments(t, new Iterable<Iterable<T>>() {

        @Override
        public Iterator<Iterable<T>> iterator() {
          return new Iterator<Iterable<T>>() {
            boolean done;
            
            @Override
            public boolean hasNext() {
              return !done;
            }

            @Override
            public void remove() {
              throw new UnsupportedOperationException();
            }

            @Override
            public Iterable<T> next() {
              if (done) {
                throw new IllegalStateException();
              }
              done = true;
              return doc;
            }
          };
        }
        });
    } else {
      w.updateDocument(t, doc);
    }
    maybeCommit();
  }
  
  public void addIndexes(Directory... dirs) throws IOException {
    LuceneTestCase.maybeChangeLiveIndexWriterConfig(r, w.getConfig());
    w.addIndexes(dirs);
  }

  public void addIndexes(IndexReader... readers) throws IOException {
    LuceneTestCase.maybeChangeLiveIndexWriterConfig(r, w.getConfig());
    w.addIndexes(readers);
  }
  
  public void updateNumericDocValue(Term term, String field, Long value) throws IOException {
    LuceneTestCase.maybeChangeLiveIndexWriterConfig(r, w.getConfig());
    w.updateNumericDocValue(term, field, value);
  }
  
  public void updateBinaryDocValue(Term term, String field, BytesRef value) throws IOException {
    LuceneTestCase.maybeChangeLiveIndexWriterConfig(r, w.getConfig());
    w.updateBinaryDocValue(term, field, value);
  }
  
  public void updateDocValues(Term term, Iterable<? extends IndexableField> updates) throws IOException {
    LuceneTestCase.maybeChangeLiveIndexWriterConfig(r, w.getConfig());
    w.updateDocValues(term, updates);
  }
  
  public void deleteDocuments(Term term) throws IOException {
    LuceneTestCase.maybeChangeLiveIndexWriterConfig(r, w.getConfig());
    w.deleteDocuments(term);
  }

  public void deleteDocuments(Query q) throws IOException {
    LuceneTestCase.maybeChangeLiveIndexWriterConfig(r, w.getConfig());
    w.deleteDocuments(q);
  }
  
  public void commit() throws IOException {
    LuceneTestCase.maybeChangeLiveIndexWriterConfig(r, w.getConfig());
    w.commit();
  }
  
  public int numDocs() {
    return w.numDocs();
  }

  public int maxDoc() {
    return w.maxDoc();
  }

  public void deleteAll() throws IOException {
    w.deleteAll();
  }

  public DirectoryReader getReader() throws IOException {
    LuceneTestCase.maybeChangeLiveIndexWriterConfig(r, w.getConfig());
    return getReader(true);
  }

  private boolean doRandomForceMerge = true;
  private boolean doRandomForceMergeAssert = true;

  public void forceMergeDeletes(boolean doWait) throws IOException {
    LuceneTestCase.maybeChangeLiveIndexWriterConfig(r, w.getConfig());
    w.forceMergeDeletes(doWait);
  }

  public void forceMergeDeletes() throws IOException {
    LuceneTestCase.maybeChangeLiveIndexWriterConfig(r, w.getConfig());
    w.forceMergeDeletes();
  }

  public void setDoRandomForceMerge(boolean v) {
    doRandomForceMerge = v;
  }

  public void setDoRandomForceMergeAssert(boolean v) {
    doRandomForceMergeAssert = v;
  }

  private void doRandomForceMerge() throws IOException {
    if (doRandomForceMerge) {
      final int segCount = w.getSegmentCount();
      if (r.nextBoolean() || segCount == 0) {
        // full forceMerge
        if (LuceneTestCase.VERBOSE) {
          System.out.println("RIW: doRandomForceMerge(1)");
        }
        w.forceMerge(1);
      } else if (r.nextBoolean()) {
        // partial forceMerge
        final int limit = TestUtil.nextInt(r, 1, segCount);
        if (LuceneTestCase.VERBOSE) {
          System.out.println("RIW: doRandomForceMerge(" + limit + ")");
        }
        w.forceMerge(limit);
        assert !doRandomForceMergeAssert || w.getSegmentCount() <= limit: "limit=" + limit + " actual=" + w.getSegmentCount();
      } else {
        if (LuceneTestCase.VERBOSE) {
          System.out.println("RIW: do random forceMergeDeletes()");
        }
        w.forceMergeDeletes();
      }
    }
  }

  public DirectoryReader getReader(boolean applyDeletions) throws IOException {
    LuceneTestCase.maybeChangeLiveIndexWriterConfig(r, w.getConfig());
    getReaderCalled = true;
    if (r.nextInt(20) == 2) {
      doRandomForceMerge();
    }
    if (!applyDeletions || r.nextBoolean()) {
      if (LuceneTestCase.VERBOSE) {
        System.out.println("RIW.getReader: use NRT reader");
      }
      if (r.nextInt(5) == 1) {
        w.commit();
      }
      return w.getReader(applyDeletions);
    } else {
      if (LuceneTestCase.VERBOSE) {
        System.out.println("RIW.getReader: open new reader");
      }
      w.commit();
      if (r.nextBoolean()) {
        return DirectoryReader.open(w.getDirectory());
      } else {
        return w.getReader(applyDeletions);
      }
    }
  }

  /**
   * Close this writer.
   * @see IndexWriter#close()
   */
  @Override
  public void close() throws IOException {
    if (w.isClosed() == false) {
      LuceneTestCase.maybeChangeLiveIndexWriterConfig(r, w.getConfig());
    }
    // if someone isn't using getReader() API, we want to be sure to
    // forceMerge since presumably they might open a reader on the dir.
    if (getReaderCalled == false && r.nextInt(8) == 2 && w.isClosed() == false) {
      doRandomForceMerge();
      if (w.getConfig().getCommitOnClose() == false) {
        // index may have changed, must commit the changes, or otherwise they are discarded by the call to close()
        w.commit();
      }
    }
    w.close();
  }

  /**
   * Forces a forceMerge.
   * <p>
   * NOTE: this should be avoided in tests unless absolutely necessary,
   * as it will result in less test coverage.
   * @see IndexWriter#forceMerge(int)
   */
  public void forceMerge(int maxSegmentCount) throws IOException {
    LuceneTestCase.maybeChangeLiveIndexWriterConfig(r, w.getConfig());
    w.forceMerge(maxSegmentCount);
  }
  
  static final class TestPointInfoStream extends InfoStream {
    private final InfoStream delegate;
    private final TestPoint testPoint;
    
    public TestPointInfoStream(InfoStream delegate, TestPoint testPoint) {
      this.delegate = delegate == null ? new NullInfoStream(): delegate;
      this.testPoint = testPoint;
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }

    @Override
    public void message(String component, String message) {
      if ("TP".equals(component)) {
        testPoint.apply(message);
      }
      if (delegate.isEnabled(component)) {
        delegate.message(component, message);
      }
    }
    
    @Override
    public boolean isEnabled(String component) {
      return "TP".equals(component) || delegate.isEnabled(component);
    }
  }
  
  /**
   * Simple interface that is executed for each <tt>TP</tt> {@link InfoStream} component
   * message. See also {@link RandomIndexWriter#mockIndexWriter(Directory, IndexWriterConfig, TestPoint)}
   */
  public static interface TestPoint {
    public abstract void apply(String message);
  }
}
