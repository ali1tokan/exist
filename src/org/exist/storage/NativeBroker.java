/*
 *  eXist Open Source Native XML Database
 *  Copyright (C) 2001-06 Wolfgang M. Meier
 *  wolfgang@exist-db.org
 *  http://exist-db.org
 *
 *  This program is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 * 
 *  $Id$
 */
package org.exist.storage;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Observer;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;
import org.exist.EXistException;
import org.exist.collections.Collection;
import org.exist.collections.CollectionCache;
import org.exist.collections.CollectionConfiguration;
import org.exist.collections.triggers.TriggerException;
import org.exist.dom.AttrImpl;
import org.exist.dom.BinaryDocument;
import org.exist.dom.DocumentImpl;
import org.exist.dom.DocumentMetadata;
import org.exist.dom.DocumentSet;
import org.exist.dom.ElementImpl;
import org.exist.dom.ExtArrayNodeSet;
import org.exist.dom.NodeIndexListener;
import org.exist.dom.NodeListImpl;
import org.exist.dom.NodeProxy;
import org.exist.dom.NodeSet;
import org.exist.dom.NodeSetHelper;
import org.exist.dom.QName;
import org.exist.dom.StoredNode;
import org.exist.dom.TextImpl;
import org.exist.memtree.DOMIndexer;
import org.exist.security.MD5;
import org.exist.security.Permission;
import org.exist.security.PermissionDeniedException;
import org.exist.security.SecurityManager;
import org.exist.security.User;
import org.exist.storage.btree.BTree;
import org.exist.storage.btree.BTreeCallback;
import org.exist.storage.btree.BTreeException;
import org.exist.storage.btree.DBException;
import org.exist.storage.btree.IndexQuery;
import org.exist.storage.btree.Paged;
import org.exist.storage.btree.Value;
import org.exist.storage.dom.DOMFile;
import org.exist.storage.dom.DOMFileIterator;
import org.exist.storage.dom.DOMTransaction;
import org.exist.storage.dom.NodeIterator;
import org.exist.storage.index.BFile;
import org.exist.storage.index.CollectionStore;
import org.exist.storage.io.VariableByteInput;
import org.exist.storage.io.VariableByteOutputStream;
import org.exist.storage.lock.Lock;
import org.exist.storage.serializers.NativeSerializer;
import org.exist.storage.serializers.Serializer;
import org.exist.storage.sync.Sync;
import org.exist.storage.txn.TransactionException;
import org.exist.storage.txn.TransactionManager;
import org.exist.storage.txn.Txn;
import org.exist.util.ByteArrayPool;
import org.exist.util.ByteConversion;
import org.exist.util.Collations;
import org.exist.util.Configuration;
import org.exist.util.LockException;
import org.exist.util.ReadOnlyException;
import org.exist.util.sanity.SanityCheck;
import org.exist.xmldb.XmldbURI;
import org.exist.xquery.Constants;
import org.exist.xquery.TerminatedException;
import org.exist.xquery.value.StringValue;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentType;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 *  Main class for the native XML storage backend.
 *  By "native" it is meant file-based, embedded backend.
 * 
 * Provides access to all low-level operations required by
 * the database. Extends {@link DBBroker}.
 * 
 * Observer Design Pattern: role : this class is the subject (alias observable)
 * for various classes that generate indices for the database content :
 * @link org.exist.storage.NativeElementIndex,
 * @link org.exist.storage.NativeTextEngine,
 * @link org.exist.storage.NativeValueIndex, 
 * @link org.exist.storage.NativeValueIndexByQName
 * 
 * This class dispatches the various events (defined by the methods 
 * of @link org.exist.storage.ContentLoadingObserver) to indexing classes.
 * 
 *@author     Wolfgang Meier
 */
public class NativeBroker extends DBBroker {
	
    public static final byte PREPEND_DB_ALWAYS = 0;
    public static final byte PREPEND_DB_NEVER = 1;
    public static final byte PREPEND_DB_AS_NEEDED = 2;
    
    public static final byte COLLECTIONS_DBX_ID = 0;
    public static final byte ELEMENTS_DBX_ID = 1;
    public static final byte VALUES_DBX_ID = 2;
    public static final byte WORDS_DBX_ID = 3;
    public static final byte DOM_DBX_ID = 4;
    public static final byte VALUES_QNAME_DBX_ID = 5;
    
    public static final String ELEMENTS_DBX = "elements.dbx";
    public static final String VALUES_DBX = "values.dbx";
    public static final String VALUES_QNAME_DBX = "values-by-qname.dbx";
    public static final String DOM_DBX = "dom.dbx";
    public static final String COLLECTIONS_DBX = "collections.dbx";
    public static final String WORDS_DBX = "words.dbx";
    
    private static final byte[] ALL_STORAGE_FILES = {
    	COLLECTIONS_DBX_ID, ELEMENTS_DBX_ID, VALUES_DBX_ID,
    	VALUES_QNAME_DBX_ID, WORDS_DBX_ID, DOM_DBX_ID
    };
    
    private static final String TEMP_FRAGMENT_REMOVE_ERROR = "Could not remove temporary fragment";
	// private static final String TEMP_STORE_ERROR = "An error occurred while storing temporary data: ";
	private static final String EXCEPTION_DURING_REINDEX = "exception during reindex";
	private static final String DATABASE_IS_READ_ONLY = "database is read-only";
	
	/**
     * Log4J Logger for this class
     */
    private static final Logger LOG = Logger.getLogger(NativeBroker.class);
    
    public final String DEFAULT_DATA_DIR = "data";
    public final int DEFAULT_PAGE_SIZE = 4096;
    public final int DEFAULT_INDEX_DEPTH = 1;
    public final int DEFAULT_MIN_MEMORY = 5000000;
    public static final long TEMP_FRAGMENT_TIMEOUT = 300000;
    /** default buffer size setting */
    public final static int BUFFERS = 256;
    /** check available memory after storing DEFAULT_NODES_BEFORE_MEMORY_CHECK nodes */
    public final static int DEFAULT_NODES_BEFORE_MEMORY_CHECK = 10000;     
    public final double DEFAULT_VALUE_CACHE_GROWTH = 1.25;
    public final double DEFAULT_VALUE_KEY_THRESHOLD = 0.01;
    public final double DEFAULT_VALUE_VALUE_THRESHOLD = 0.04;
    public final double DEFAULT_WORD_CACHE_GROWTH = 1.4;
    public final double DEFAULT_WORD_KEY_THRESHOLD = 0.01;  
    public final double DEFAULT_WORD_VALUE_THRESHOLD = 0.015;
    
    

	/** the database files */
	protected CollectionStore collectionsDb;
	protected DOMFile domDb;
	protected BFile elementsDb;
	protected BFile valuesDb;
    protected BFile dbWords;
	protected BFile valuesDbQname;
    
	/** the index processors */
	protected NativeTextEngine textEngine;
	protected NativeElementIndex elementIndex;
	protected NativeValueIndex valueIndex;
	protected NativeValueIndexByQName qnameValueIndex;
    
    protected IndexSpec idxConf;
    
    protected int defaultIndexDepth;    
    
	/** switch to activate/deactivate the feature "new index by QName" */
	private boolean qnameValueIndexation = true; // false;
	
	protected Serializer xmlSerializer;		
	
	protected boolean readOnly = false;
	
	protected int memMinFree;
	
	/** used to count the nodes inserted after the last memory check */
	protected int nodesCount = 0;

    protected String dataDir;
	protected int pageSize;
	
	protected byte prepend;
	
	private final Runtime run = Runtime.getRuntime();

    private NodeProcessor nodeProcessor = new NodeProcessor();
    
	/** initialize database; read configuration, etc. */
	public NativeBroker(BrokerPool pool, Configuration config) throws EXistException {
		super(pool, config);
		LOG.debug("Initializing broker " + hashCode());
        
        String prependDB = (String) config.getProperty("db-connection.prepend-db");
		if ("always".equalsIgnoreCase(prependDB)) {
            prepend = PREPEND_DB_ALWAYS;
		} else if("never".equalsIgnoreCase(prependDB)) {
			prepend = PREPEND_DB_NEVER;
		} else {
			prepend = PREPEND_DB_AS_NEEDED;
		}

        dataDir = (String) config.getProperty("db-connection.data-dir");
		if (dataDir == null)
            dataDir = DEFAULT_DATA_DIR;

        pageSize = config.getInteger("db-connection.page-size");
		if (pageSize < 0)
            pageSize = DEFAULT_PAGE_SIZE;
        Paged.setPageSize(pageSize);

        defaultIndexDepth = config.getInteger("indexer.index-depth");
		if (defaultIndexDepth < 0)
			defaultIndexDepth = DEFAULT_INDEX_DEPTH;
        
        memMinFree = config.getInteger("db-connection.min_free_memory");
		if (memMinFree < 0)
			memMinFree = DEFAULT_MIN_MEMORY;
        
        idxConf = (IndexSpec) config.getProperty("indexer.config");         
        xmlSerializer = new NativeSerializer(this, config);
        user = SecurityManager.SYSTEM_USER;            
        
        readOnly = pool.isReadOnly();
		try {

            // Initialize DOM storage     
            domDb = (DOMFile) config.getProperty("db-connection.dom");
			if (domDb== null) {
                File file= new File(dataDir + File.separatorChar + DOM_DBX);
                LOG.debug("Creating '" + file.getName() + "'...");
				domDb =	new DOMFile(pool, file, pool.getCacheManager());
				config.setProperty("db-connection.dom", domDb);				
			}
            readOnly = readOnly || domDb.isReadOnly();
            
			// Initialize collections storage            
            collectionsDb = (CollectionStore) config.getProperty("db-connection.collections");
			if (collectionsDb == null) {
                File file = new File(dataDir + File.separatorChar + COLLECTIONS_DBX);
                LOG.debug("Creating '" + file.getName() + "'...");
				collectionsDb = new CollectionStore(pool, file, pool.getCacheManager());
				config.setProperty("db-connection.collections", collectionsDb);				
            }
            readOnly = readOnly || collectionsDb.isReadOnly();
            
            //TODO : is it necessary to create them if we are in read-only mode ?
			createIndexFiles();
			
			if (readOnly)
				LOG.info("Database runs in read-only mode");

		} catch (DBException e) {
			LOG.debug(e.getMessage(), e);
			throw new EXistException(e);
		}
	}

    private void createIndexFiles() throws DBException {
        elementsDb = createValueIndexFile(ELEMENTS_DBX_ID, false, config, dataDir, ELEMENTS_DBX, "db-connection.elements", DEFAULT_VALUE_VALUE_THRESHOLD);
        elementIndex = new NativeElementIndex(this, elementsDb);
        addContentLoadingObserver(elementIndex);
        
        valuesDb = createValueIndexFile(VALUES_DBX_ID, false, config, dataDir, VALUES_DBX, "db-connection.values", DEFAULT_VALUE_VALUE_THRESHOLD);
        valueIndex = new NativeValueIndex(this, valuesDb);
        addContentLoadingObserver(valueIndex);
        
        if (qnameValueIndexation) {
            valuesDbQname = createValueIndexFile(VALUES_QNAME_DBX_ID, false, config, dataDir, VALUES_QNAME_DBX,
                    "db-connection2.values", DEFAULT_VALUE_VALUE_THRESHOLD);
            qnameValueIndex = new NativeValueIndexByQName(this, valuesDbQname);
            addContentLoadingObserver(qnameValueIndex);            
        }        
        
        dbWords = (BFile) config.getProperty("db-connection.words");
        if (dbWords == null) {
            File file = new File(dataDir + File.separatorChar + WORDS_DBX);
            LOG.debug("Creating '" + file.getName() + "'...");
        	dbWords = new BFile(pool, NativeBroker.WORDS_DBX_ID, false, file,                     
        	        pool.getCacheManager(), DEFAULT_WORD_CACHE_GROWTH, DEFAULT_WORD_KEY_THRESHOLD, DEFAULT_WORD_VALUE_THRESHOLD);
            config.setProperty("db-connection.words", dbWords); 
        }
        textEngine = new NativeTextEngine(this, config, dbWords);
        addContentLoadingObserver(textEngine);
        readOnly = readOnly || dbWords.isReadOnly();
    }

    private BFile createValueIndexFile(byte id, boolean transactional, Configuration config, String dataDir, 
            String dataFile, String propertyName, double thresholdData ) throws DBException {
        
        BFile db = (BFile) config.getProperty(propertyName);        
        if (db == null) {
            File file = new File(dataDir + File.separatorChar + dataFile);
            LOG.debug("Creating '" + file.getName() + "'...");
            db = new BFile(pool, id, transactional, file, pool.getCacheManager(), DEFAULT_VALUE_CACHE_GROWTH, DEFAULT_VALUE_KEY_THRESHOLD, thresholdData);            
            config.setProperty(propertyName, db);            
        }
        readOnly = readOnly || db.isReadOnly();
        return db;
    }

    /** Observer Design Pattern: List of ContentLoadingObserver objects */
    private List contentLoadingObservers = new ArrayList();
    
    public void addObserver(Observer o) {
        super.addObserver(o);
        textEngine.addObserver(o);
        elementIndex.addObserver(o);
    }
    
    public void deleteObservers() {
        super.deleteObservers();
        if (elementIndex != null)
            elementIndex.deleteObservers();
        if (textEngine != null)
            textEngine.deleteObservers();
    }
    
    /** Remove all observers */
    public void clearContentLoadingObservers() {
        contentLoadingObservers.clear();
    }    
    
    /** Observer Design Pattern: add an observer. */
    public void addContentLoadingObserver(ContentLoadingObserver observer) {
        contentLoadingObservers.add(observer);
    }
    /** Observer Design Pattern: remove an observer. */
    public void removeContentLoadingObserver(ContentLoadingObserver observer) {
        contentLoadingObservers.remove(observer);
    }
    
    // ============ dispatch the various events to indexing classes ==========

    private void notifyStartElement(ElementImpl elem, NodePath currentPath, boolean index) {
        // WM: don't use an iterator here. The method may be called a few million times for a single document. 
        for (int i = 0; i < contentLoadingObservers.size(); i++) {
            ContentLoadingObserver observer = (ContentLoadingObserver) contentLoadingObservers.get(i);
            observer.startElement(elem, currentPath, index);
        }
	}
    
    private void notifyRemoveElement(ElementImpl elem, NodePath currentPath, String content) {
        for (int i = 0; i < contentLoadingObservers.size(); i++) {
            ContentLoadingObserver observer = (ContentLoadingObserver) contentLoadingObservers.get(i);
            observer.removeElement(elem, currentPath, content);
        }
    }

    private void notifyStoreAttribute(AttrImpl attr, NodePath currentPath, boolean fullTextIndex) {
        for (int i = 0; i < contentLoadingObservers.size(); i++) {
            ContentLoadingObserver observer = (ContentLoadingObserver) contentLoadingObservers.get(i);
            observer.storeAttribute( attr, currentPath, fullTextIndex );
        }	
	}	

	private void notifyStoreText(TextImpl text, NodePath currentPath, boolean fullTextIndex ) {
        for (int i = 0; i < contentLoadingObservers.size(); i++) {
            ContentLoadingObserver observer = (ContentLoadingObserver) contentLoadingObservers.get(i);
            observer.storeText(text, currentPath, fullTextIndex);
        }
	}
    
    private void notifyFlush() {
        for (int i = 0; i < contentLoadingObservers.size(); i++) {
            ContentLoadingObserver observer = (ContentLoadingObserver) contentLoadingObservers.get(i);
            observer.flush();
        }
    }    
	
    private void notifySync() {
        for (int i = 0; i < contentLoadingObservers.size(); i++) {
            ContentLoadingObserver observer = (ContentLoadingObserver) contentLoadingObservers.get(i);
            observer.sync();
        }
    }

    private void notifyReindex(final DocumentImpl oldDoc, final StoredNode node) {
        for (int i = 0; i < contentLoadingObservers.size(); i++) {
            ContentLoadingObserver observer = (ContentLoadingObserver) contentLoadingObservers.get(i);
            observer.reindex(oldDoc, node);
        }
    }

    private void notifyDropIndex(Collection collection) {
        for (int i = 0; i < contentLoadingObservers.size(); i++) {
            ContentLoadingObserver observer = (ContentLoadingObserver) contentLoadingObservers.get(i);
            observer.dropIndex(collection);
        }
    }

    private void notifyDropIndex(DocumentImpl doc) throws ReadOnlyException {
        for (int i = 0; i < contentLoadingObservers.size(); i++) {
            ContentLoadingObserver observer = (ContentLoadingObserver) contentLoadingObservers.get(i);
            observer.dropIndex(doc);
        }
    }
    
    private void notifyRemove() {
        for (int i = 0; i < contentLoadingObservers.size(); i++) {
            ContentLoadingObserver observer = (ContentLoadingObserver) contentLoadingObservers.get(i);
            observer.remove();
        }
    }
    
    // etc ... TODO for all methods of ContentLoadingObserver 
    
    /**
     * Update indexes for the given element node. This method is called when the indexer
     * encounters a closing element tag. It updates any range indexes defined on the
     * element value and adds the element id to the structural index.
     * 
     * @param node the current element node
     * @param currentPath node path leading to the element
     * @param content contains the string value of the element. Needed if a range index
     * is defined on it.
     */
    public void endElement(final StoredNode node, NodePath currentPath, String content, long oldAddress) {
        node.getQName().setNameType(ElementValue.ELEMENT);
        
        final DocumentImpl doc = (DocumentImpl) node.getOwnerDocument();
        final int indexType = ((ElementImpl) node).getIndexType();

        NodeProxy p = new NodeProxy(node);
        p.setIndexType(indexType);        
        
        // TODO move_to NativeValueIndex
        if (RangeIndexSpec.hasRangeIndex(indexType)) {
            if (content == null) {
                if (oldAddress != StoredNode.UNKNOWN_NODE_IMPL_ADDRESS)
                    p.setInternalAddress(oldAddress);
                content = getNodeValue(node, false);
                //Curious...
                p.setInternalAddress(node.getInternalAddress());
            }
            valueIndex.setDocument(doc);
            valueIndex.storeElement(RangeIndexSpec.indexTypeToXPath(indexType), 
                    (ElementImpl) node, content.toString());
        }
        
        // TODO move_to NativeValueIndexByQName 
        if ( RangeIndexSpec.hasQNameIndex(indexType) ) {
            if (content == null) {
                if (oldAddress != StoredNode.UNKNOWN_NODE_IMPL_ADDRESS)
                    p.setInternalAddress(oldAddress);
                content = getNodeValue(node, false);
                //Curious...
                p.setInternalAddress(node.getInternalAddress());
            }
            
            if (qnameValueIndex != null)
                qnameValueIndex.endElement((ElementImpl) node, currentPath, content);
        }
        
        // TODO move_to NativeElementIndex; name change (See ContentLoadingObserver ): addRow() --> endElement()
        // save element by calling ElementIndex
        elementIndex.setDocument(doc);
        elementIndex.addNode(node.getQName(), p);
    }    
    
    /** Takes care of actually remove entries from the indices;
     * must be called after one or more call to {@link #removeNode()}. */
    public void endRemove() {
        notifyRemove();
    }
    
    public int getBackendType() {
        return NATIVE;
    }
    
    public boolean isReadOnly() {
        return readOnly;
    } 
    
    public int getPageSize() {
        return pageSize;
    }    
    
    public DOMFile getDOMFile() {
        return domDb;
    }
    
    public BTree getStorage(byte id) {
        switch (id) {
            case COLLECTIONS_DBX_ID :
                return collectionsDb;
            case ELEMENTS_DBX_ID :
                return elementsDb;
            case WORDS_DBX_ID :
                return dbWords;
            case VALUES_DBX_ID :
                return valuesDb;
            case VALUES_QNAME_DBX_ID :
                return valuesDbQname;
            case DOM_DBX_ID :
                return domDb;
            default:
                return null;
        }
    }
    
    public byte[] getStorageFileIds() {
        return ALL_STORAGE_FILES;
    }        

	public IndexSpec getIndexConfiguration() {
	    return idxConf;
	}
    
    public Serializer getSerializer() {
        xmlSerializer.reset();
        return xmlSerializer;
    }
    
    public Serializer newSerializer() {
        return new NativeSerializer(this, getConfiguration());
    }    
    
    public ElementIndex getElementIndex() {
        return elementIndex;
    }    

    public NativeValueIndex getValueIndex() {
        return valueIndex;
    }
    
    public NativeValueIndexByQName getQNameValueIndex() {
        return qnameValueIndex;
    }     
    
    public TextSearchEngine getTextEngine() {
        return textEngine;
    }
    
    public Iterator getDOMIterator(StoredNode node) {
        try {
            return new DOMFileIterator(this, domDb, node);
        } catch (BTreeException e) {
            LOG.debug("failed to create DOM iterator", e);
        } catch (IOException e) {
            LOG.debug("failed to create DOM iterator", e);
        }
        return null;
    }

    public Iterator getNodeIterator(StoredNode node) {
       if (node==null) {
          throw new IllegalArgumentException("The node parameter cannot be null.");
       }
//      domDb.setOwnerObject(this);
        try {
            return new NodeIterator(this, domDb, node, false);
        } catch (BTreeException e) {
            LOG.debug("failed to create node iterator", e);
        } catch (IOException e) {
            LOG.debug("failed to create node iterator", e);
        }
        return null;
    }    
    
    /** create temporary collection */  
    private Collection createTempCollection(Txn transaction) throws LockException, PermissionDeniedException {
        User u = user;
        Lock lock = null;
        try {
            lock = collectionsDb.getLock();
            lock.acquire(Lock.WRITE_LOCK);
            user = pool.getSecurityManager().getUser(SecurityManager.DBA_USER);
            Collection temp = getOrCreateCollection(transaction, XmldbURI.TEMP_COLLECTION_URI);
            temp.setPermissions(0771);
            saveCollection(transaction, temp);
            return temp;
        } finally {
            user = u;
            lock.release();
        }
    } 
    
    /** remove temporary collection */  
    public void cleanUpTempCollection() {
        Collection temp = getCollection(XmldbURI.TEMP_COLLECTION_URI);
        if(temp == null)
            return;
        TransactionManager transact = pool.getTransactionManager();
        Txn txn = transact.beginTransaction();
        try {
            removeCollection(txn, temp);
            transact.commit(txn);
        } catch (PermissionDeniedException e) {
            transact.abort(txn);
            LOG.warn("Failed to remove temporary collection: " + e.getMessage(), e);
        } catch (TransactionException e) {
            transact.abort(txn);
            LOG.warn("Failed to remove temporary collection: " + e.getMessage(), e);
        }
    }
    
    public XmldbURI prepend(XmldbURI uri) {
    	switch(prepend) {
    	case PREPEND_DB_ALWAYS:
    		return uri.prepend(XmldbURI.ROOT_COLLECTION_URI);
    	case PREPEND_DB_AS_NEEDED:
    		return uri.startsWith(XmldbURI.ROOT_COLLECTION_URI)?uri:uri.prepend(XmldbURI.ROOT_COLLECTION_URI);
    	default:
    		return uri;
    	}
    }
    
    public Collection getOrCreateCollection(Txn transaction, XmldbURI name) throws PermissionDeniedException {
    	name = prepend(name.normalizeCollectionPath());
        final CollectionCache collectionsCache = pool.getCollectionsCache();
        synchronized(collectionsCache) {   
            try {
                XmldbURI[] segments = name.getPathSegments();
                XmldbURI path = XmldbURI.ROOT_COLLECTION_URI;
                Collection sub;
                Collection current = getCollection(XmldbURI.ROOT_COLLECTION_URI);
                if (current == null) {
                    LOG.debug("Creating root collection '" + XmldbURI.ROOT_COLLECTION_URI + "'");
                    current = new Collection(XmldbURI.ROOT_COLLECTION_URI);
                    current.getPermissions().setPermissions(0777);
                    current.getPermissions().setOwner(user);
                    current.getPermissions().setGroup(user.getPrimaryGroup());
                    current.setId(getNextCollectionId(transaction));
                    current.setCreationTime(System.currentTimeMillis());
                    if (transaction != null)
                        transaction.acquireLock(current.getLock(), Lock.WRITE_LOCK);
                    saveCollection(transaction, current);
                }
                for(int i=1;i<segments.length;i++) {
                    XmldbURI temp = segments[i];
                    path = path.append(temp);
                    if (current.hasSubcollection(temp)) {
                        current = getCollection(path);
                        if (current == null)
                            LOG.debug("Collection '" + path + "' not found!");
                    } else {
                        if (readOnly)
                            throw new PermissionDeniedException(DATABASE_IS_READ_ONLY);
                        if (!current.getPermissions().validate(user, Permission.WRITE)) {
                            LOG.error("Permission denied to create collection '" + path + "'");
                            throw new PermissionDeniedException("User '"+ user.getName() + "' not allowed to write to collection '" + current.getURI() + "'");
                        }
                        LOG.debug("Creating collection '" + path + "'...");
                        sub = new Collection(path);
                        sub.getPermissions().setOwner(user);
                        sub.getPermissions().setGroup(user.getPrimaryGroup());
                        sub.setId(getNextCollectionId(transaction));
                        sub.setCreationTime(System.currentTimeMillis());
                        if (transaction != null)
                            transaction.acquireLock(sub.getLock(), Lock.WRITE_LOCK);
                        current.addCollection(this, sub, true);
                        saveCollection(transaction, current);
                        current = sub;
                    }
                }
                return current;  
            } catch (LockException e) {
                LOG.warn("Failed to acquire lock on " + collectionsDb.getFile().getName());
                return null;                
            } catch (ReadOnlyException e) {
                throw new PermissionDeniedException(DATABASE_IS_READ_ONLY);
            }                
        }
    }    

	public Collection getCollection(XmldbURI name) {
		return openCollection(name, BFile.UNKNOWN_ADDRESS, Lock.NO_LOCK);
	}
	
	public Collection getCollection(XmldbURI name, long addr) {
		return openCollection(name, addr, Lock.NO_LOCK);
	}
	
	public Collection openCollection(XmldbURI name, int lockMode) {
		return openCollection(name, BFile.UNKNOWN_ADDRESS, lockMode);
	}

	/**
	 *  Get collection object. If the collection does not exist, null is
	 *  returned.
	 *
	 *@param  name  collection name
	 *@return       The collection value
	 */
	public Collection openCollection(XmldbURI name, long addr, int lockMode) {
	    name = prepend(name.toCollectionPathURI());
	    Collection collection;
	    final CollectionCache collectionsCache = pool.getCollectionsCache();        
	    synchronized(collectionsCache) {      
	        collection = collectionsCache.get(name);
	        if (collection == null) {				
	            final Lock lock = collectionsDb.getLock();
	            try {
	                lock.acquire(Lock.READ_LOCK);
	                VariableByteInput is;
	                if (addr == BFile.UNKNOWN_ADDRESS) {
	                    Value key = new Value(name.toString().getBytes("UTF-8"));
	                    is = collectionsDb.getAsStream(key);
	                } else {
	                    is = collectionsDb.getAsStream(addr);
	                }
	                if (is == null) return null;   
	                
	                collection = new Collection(name);
	                collection.read(this, is);
	                
	                //TODO : manage this from within the cache -pb
	                if(!pool.isInitializing())
	                    collectionsCache.add(collection);
	                
	                //TODO : rethrow exceptions ? -pb
	            } catch (UnsupportedEncodingException e) {
	                LOG.error("Unable to encode '" + name + "' in UTF-8");
	                return null;
	            } catch (LockException e) {
	                LOG.warn("Failed to acquire lock on " + collectionsDb.getFile().getName());
	                return null;
	            } catch (IOException e) {
	                LOG.error(e.getMessage(), e);
	                return null;
	            } finally {
	                lock.release();
	            }
	        }         
	    }
        //Important : 
        //This code must remain ouside of the synchonized block
        //because another thread may already own a lock on the collection
        //This would result in a deadlock... until the time-out raises the Exception
        //TODO : make an attempt to an immediate lock ?
        //TODO : manage a collection of requests for locks ?
        //TODO : another yet smarter solution ?
        if(lockMode != Lock.NO_LOCK) {
            try {
                collection.getLock().acquire(lockMode);
            } catch (LockException e) {
                LOG.warn("Failed to acquire lock on collection '" + name + "'");
            }
        }
        return collection;           
	}
    
   public void copyCollection(Txn transaction, Collection collection, Collection destination, XmldbURI newName)
   throws PermissionDeniedException, LockException {
        if (readOnly)
            throw new PermissionDeniedException(DATABASE_IS_READ_ONLY);
 	   if(newName!=null && newName.numSegments()!=1) {
		   throw new PermissionDeniedException("New collection name must have one segment!");
	   }
        if(!collection.getPermissions().validate(user, Permission.READ))
            throw new PermissionDeniedException("Read permission denied on collection " +
                    collection.getURI());
        if(collection.getId() == destination.getId())
            throw new PermissionDeniedException("Cannot move collection to itself");
        if(!destination.getPermissions().validate(user, Permission.WRITE))
            throw new PermissionDeniedException("Insufficient privileges on target collection " +
                    destination.getURI());
        if(newName == null) {
            newName = collection.getURI().lastSegment();
        }
        //  check if another collection with the same name exists at the destination
        Collection old = openCollection(destination.getURI().append(newName), Lock.WRITE_LOCK);
        if(old != null) {
            LOG.debug("removing old collection: " + newName);
            try {
                removeCollection(transaction, old);
            } finally {
                old.release();
            }
        }
        Collection destCollection = null;
        Lock lock = null;
        try {
            lock = collectionsDb.getLock();
            lock.acquire(Lock.WRITE_LOCK);
            newName = destination.getURI().append(newName);
            LOG.debug("Copying collection to '" + newName + "'");
            destCollection = getOrCreateCollection(transaction, newName);
            for(Iterator i = collection.iterator(this); i.hasNext(); ) {
                DocumentImpl child = (DocumentImpl) i.next();
                LOG.debug("Copying resource: '" + child.getURI() + "'");
                if (child.getResourceType() == DocumentImpl.XML_FILE) {
                    DocumentImpl newDoc = new DocumentImpl(this, destCollection, child.getFileURI());
                    newDoc.copyOf(child);
                    newDoc.setDocId(getNextResourceId(transaction, destination));
                    copyXMLResource(transaction, child, newDoc);
                    storeXMLResource(transaction, newDoc);
                    destCollection.addDocument(transaction, this, newDoc);
                } else {
                    BinaryDocument newDoc = new BinaryDocument(this, destCollection, child.getFileURI());
                    newDoc.copyOf(child);
                    newDoc.setDocId(getNextResourceId(transaction, destination));
                    byte[] data = getBinaryResource((BinaryDocument) child);
                    storeBinaryResource(transaction, newDoc, data);
                    storeXMLResource(transaction, newDoc);
                    destCollection.addDocument(transaction, this, newDoc);
                }
            }
            saveCollection(transaction, destCollection);
        } finally {
            lock.release();
        }

        XmldbURI name = collection.getURI();
        for(Iterator i = collection.collectionIterator(); i.hasNext(); ) {
        	XmldbURI childName = (XmldbURI)i.next();
            Collection child = openCollection(name.append(childName), Lock.WRITE_LOCK);
            if(child == null)
                LOG.warn("Child collection '" + childName + "' not found");
            else {
                try {
                    copyCollection(transaction, child, destCollection, childName);
                } finally {
                    child.release();
                }
            }
        }
        saveCollection(transaction, destCollection);
        saveCollection(transaction, destination);
    }
    
    public void moveCollection(Txn transaction, Collection collection, Collection destination, XmldbURI newName) 
    throws PermissionDeniedException, LockException {
        if (readOnly)
            throw new PermissionDeniedException(DATABASE_IS_READ_ONLY);
 	   if(newName!=null && newName.numSegments()!=1) {
		   throw new PermissionDeniedException("New collection name must have one segment!");
	   }
        if(collection.getId() == destination.getId())
            throw new PermissionDeniedException("Cannot move collection to itself");
        if(collection.getURI().equals(XmldbURI.ROOT_COLLECTION_URI))
            throw new PermissionDeniedException("Cannot move the db root collection");
        if(!collection.getPermissions().validate(user, Permission.WRITE))
            throw new PermissionDeniedException("Insufficient privileges to move collection " +
                    collection.getURI());
        if(!destination.getPermissions().validate(user, Permission.WRITE))
            throw new PermissionDeniedException("Insufficient privileges on target collection " +
                    destination.getURI());
            // check if another collection with the same name exists at the destination
        Collection old = openCollection(destination.getURI().append(newName), Lock.WRITE_LOCK);
        if(old != null) {
            try {
                removeCollection(transaction, old);
            } finally {
                old.release();
            }
        }

        XmldbURI name = collection.getURI();
        final CollectionCache collectionsCache = pool.getCollectionsCache();
        synchronized(collectionsCache) {
            Collection parent = openCollection(collection.getParentURI(), Lock.WRITE_LOCK);
            if(parent != null) {
                try {
                    parent.removeCollection(name.lastSegment());
                } finally {
                    parent.release();
                }
            }
            Lock lock = null;
            try {
                lock = collectionsDb.getLock();
                lock.acquire(Lock.WRITE_LOCK);
                
                collectionsCache.remove(collection);
                Value key;
                try {
                    key = new Value(name.toString().getBytes("UTF-8"));
                } catch (UnsupportedEncodingException uee) {
                    key = new Value(name.toString().getBytes());
                }   
                collectionsDb.remove(transaction, key);

                collection.setPath(destination.getURI().append(newName));
                collection.setCreationTime(System.currentTimeMillis());
                
                destination.addCollection(this, collection, false);
                if(parent != null)
                    saveCollection(transaction, parent);
                if(parent != destination)
                    saveCollection(transaction, destination);
                saveCollection(transaction, collection);
            } catch (ReadOnlyException e) {
                throw new PermissionDeniedException(DATABASE_IS_READ_ONLY);
            } finally {
                lock.release();
            }
            
            XmldbURI childName;
            Collection child;
            for(Iterator i = collection.collectionIterator(); i.hasNext(); ) {
                childName = (XmldbURI)i.next();
                child = openCollection(name.append(childName), Lock.WRITE_LOCK);
                if(child == null)
                    LOG.warn("Child collection " + childName + " not found");
                else {
                    try {
                        moveCollection(transaction, child, collection, childName);
                    } finally {
                        child.release();
                    }
                }
            }
        }
    }    
    
    public boolean removeCollection(final Txn transaction, Collection collection) throws PermissionDeniedException {
        if (readOnly)
            throw new PermissionDeniedException(DATABASE_IS_READ_ONLY);        
        if (!collection.getPermissions().validate(user, Permission.WRITE))
            throw new PermissionDeniedException("User '"+ user.getName() + "' not allowed to remove collection '" + collection.getURI() + "'");
        long start = System.currentTimeMillis();
        final CollectionCache collectionsCache = pool.getCollectionsCache();
        synchronized(collectionsCache) {
            final XmldbURI name = collection.getURI();
            final boolean isRoot = collection.getParentURI() == null;
            //Drop all index entries
            notifyDropIndex(collection);            
            if (!isRoot) {
                // remove from parent collection
                Collection parent = openCollection(collection.getParentURI(), Lock.WRITE_LOCK);
                // keep the lock for the transaction
                if (transaction != null)
                    transaction.registerLock(parent.getLock(), Lock.WRITE_LOCK);
                if (parent != null) {
                    try {
                        LOG.debug("Removing collection '" + name + "' from its parent...");
                        //TODO : resolve from collection's base URI
                        parent.removeCollection(name.lastSegment());
                        saveCollection(transaction, parent);
                    } catch (LockException e) {
                        LOG.warn("LockException while removing collection '" + name + "'");
                    } finally {
                        if (transaction == null)
                            parent.getLock().release();
                    }
                }
            }
            // remove child collections
            LOG.debug("Removing children collections from their parent '" + name + "'...");
            for (Iterator i = collection.collectionIterator(); i.hasNext();) {
                final XmldbURI childName = (XmldbURI) i.next();
                //TODO : resolve from collection's base URI
                Collection childCollection = openCollection(name.append(childName), Lock.WRITE_LOCK);
                try {                    
                    removeCollection(transaction, childCollection);                    
                } finally {
                    childCollection.getLock().release();
                }
            }
            //Update current state
            Lock lock = collectionsDb.getLock();
            try {
                lock.acquire(Lock.WRITE_LOCK);
                // if this is not the root collection remove it...
                if (!isRoot) {
                    Value key;
                    try {
                        key = new Value(name.toString().getBytes("UTF-8"));
                    } catch (UnsupportedEncodingException e) {
                        //TODO : real exception ; we are in trouble ! -pb
                        LOG.error("Can not encode '" + name + "' in UTF-8", e);
                        key = new Value(name.toString().getBytes());
                    }  
                    //... from the disk
                    collectionsDb.remove(transaction, key);
                    //... from the cache
                    collectionsCache.remove(collection);
                    //and free its id for any futher use
                    freeCollectionId(transaction, collection.getId());
                } else {
                    //Simply save the collection on disk
                    //It will remain cached
                    //and its id well never be made available 
                    saveCollection(transaction, collection);
                }                
            } catch (LockException e) {
                LOG.warn("Failed to acquire lock on '" + collectionsDb.getFile().getName() + "'");
            } catch (ReadOnlyException e) {
                throw new PermissionDeniedException(DATABASE_IS_READ_ONLY);
            } finally {
                lock.release();
            }
            //Remove child resources
            LOG.debug("Removing resources in '" + name + "'...");
            for (Iterator i = collection.iterator(this); i.hasNext();) {
                final DocumentImpl doc = (DocumentImpl) i.next();
                //Remove doc's metadata
                removeResourceMetadata(transaction, doc);
                //Remove document nodes' index entries
                new DOMTransaction(this, domDb, Lock.WRITE_LOCK) {
                    public Object start() {
                        try {                          
                            Value ref = new NodeRef(doc.getDocId());
                            IndexQuery query = new IndexQuery(IndexQuery.TRUNC_RIGHT, ref);                           
                            domDb.remove(transaction, query, null);
                        } catch (BTreeException e) {
                            LOG.warn("btree error while removing document", e);
                        } catch (IOException e) {
                            LOG.warn("io error while removing document", e);
                        } catch (TerminatedException e) {
                            LOG.warn("method terminated", e);
                        }
                        return null;
                    }
                }
                .run();  
                //Remove nodes themselves
                new DOMTransaction(this, domDb, Lock.WRITE_LOCK) {
                    public Object start() {
                        if(doc.getResourceType() == DocumentImpl.BINARY_FILE) {
                        	long page = ((BinaryDocument)doc).getPage();
                        	if (page > -1)
                        		domDb.removeOverflowValue(transaction, page);
                        } else {                            
                            StoredNode node = (StoredNode)doc.getFirstChild();
                            domDb.removeAll(transaction, node.getInternalAddress());
                        }
                        return null;
                    }
                }
                .run();
                //Make doc's id available again
                freeResourceId(transaction, doc.getDocId());
            }
            LOG.debug("Removing collection '" + name + "' took " + (System.currentTimeMillis() - start));
            return true;
        }
    }
    
    /**
     * Saves the specified collection to storage. Collections are usually cached in
     * memory. If a collection is modified, this method needs to be called to make
     * the changes persistent.
     * 
     * Note: appending a new document to a collection does not require a save.
     * Instead, {@link #addDocument(Collection, DocumentImpl)} is called.
     */
    public void saveCollection(Txn transaction, Collection collection) throws PermissionDeniedException {
        if (collection == null) {
            LOG.error("NativeBroker.saveCollection called with collection == null! Aborting.");
            return;
        }
        if (readOnly)
            throw new PermissionDeniedException(DATABASE_IS_READ_ONLY);
        if (!pool.isInitializing())
            // don't cache the collection during initialization: SecurityManager is not yet online
            pool.getCollectionsCache().add(collection);
        
        Lock lock = collectionsDb.getLock();
        try {           
            lock.acquire(Lock.WRITE_LOCK);

            if (collection.getId() == Collection.UNKNOWN_COLLECTION_ID)
                collection.setId(getNextCollectionId(transaction));

            Value name;
            try {
                name = new Value(collection.getURI().toString().getBytes("UTF-8"));
            } catch (UnsupportedEncodingException uee) {
                LOG.debug(uee);
                name = new Value(collection.getURI().toString().getBytes());
            }
            
            try {
                final VariableByteOutputStream ostream = new VariableByteOutputStream(8);
                collection.write(this, ostream);
                final long addr = collectionsDb.put(transaction, name, ostream.data(), true);
                if (addr == BFile.UNKNOWN_ADDRESS) {
                    //TODO : exception !!! -pb
                    LOG.warn("could not store collection data for '" + collection.getURI()+ "'");
                    return;
                }
                collection.setAddress(addr);
                ostream.close();
            } catch (IOException ioe) {
                LOG.debug(ioe);
            }
        } catch (ReadOnlyException e) {
            LOG.warn(DATABASE_IS_READ_ONLY);
        } catch (LockException e) {
            LOG.warn("Failed to acquire lock on " + collectionsDb.getFile().getName(), e);
        } finally {
            lock.release();
        }
    }
    
    /**
     * Release the collection id assigned to a collection so it can be
     * reused later.
     * 
     * @param id
     * @throws PermissionDeniedException
     */
    protected void freeCollectionId(Txn transaction, short id) throws PermissionDeniedException {       
        Lock lock = collectionsDb.getLock();
        try {
            lock.acquire(Lock.WRITE_LOCK);
            Value key = new Value(CollectionStore.FREE_COLLECTION_ID_KEY);
            Value value = collectionsDb.get(key);
            if (value != null) {
                byte[] data = value.getData();
                byte[] ndata = new byte[data.length + 2];
                System.arraycopy(data, 0, ndata, 2, data.length);
                ByteConversion.shortToByte(id, ndata, 0);
                collectionsDb.put(transaction, key, ndata, true);
            } else {
                byte[] data = new byte[2];
                ByteConversion.shortToByte(id, data, 0);
                collectionsDb.put(transaction, key, data, true);
            }
        } catch (LockException e) {
            LOG.warn("Failed to acquire lock on " + collectionsDb.getFile().getName(), e);
            //TODO : rethrow ? -pb
        } catch (ReadOnlyException e) {
            throw new PermissionDeniedException(DATABASE_IS_READ_ONLY);
        } finally {
            lock.release();
        }
    }
    
    /**
     * Get the next free collection id. If a collection is removed, its collection id
     * is released so it can be reused.
     * 
     * @return
     * @throws ReadOnlyException
     */
    public short getFreeCollectionId(Txn transaction) throws ReadOnlyException {
        short freeCollectionId = Collection.UNKNOWN_COLLECTION_ID;      
        Lock lock = collectionsDb.getLock();
        try {
            lock.acquire(Lock.WRITE_LOCK);
            Value key = new Value(CollectionStore.FREE_COLLECTION_ID_KEY);
            Value value = collectionsDb.get(key);
            if (value != null) {
                byte[] data = value.getData();
                freeCollectionId = ByteConversion.byteToShort(data, data.length - 2);
//              LOG.debug("reusing collection id: " + freeCollectionId);
                if(data.length - 2 > 0) {
                    byte[] ndata = new byte[data.length - 2];
                    System.arraycopy(data, 0, ndata, 0, ndata.length);
                    collectionsDb.put(transaction, key, ndata, true);
                } else
                    collectionsDb.remove(transaction, key);
            }
            return freeCollectionId;
        } catch (LockException e) {
            LOG.warn("Failed to acquire lock on " + collectionsDb.getFile().getName(), e);
            return Collection.UNKNOWN_COLLECTION_ID;
            //TODO : rethrow ? -pb
        } finally {
            lock.release();
        }
    }
    
    /**
     * Get the next available unique collection id.
     * 
     * @return
     * @throws ReadOnlyException
     */
    public short getNextCollectionId(Txn transaction) throws ReadOnlyException {
        
        short nextCollectionId = getFreeCollectionId(transaction);
        
        if(nextCollectionId != Collection.UNKNOWN_COLLECTION_ID)
            return nextCollectionId;        
        
        Lock lock = collectionsDb.getLock();
        try {
            lock.acquire(Lock.WRITE_LOCK);
            Value key = new Value(CollectionStore.NEXT_COLLECTION_ID_KEY);
            Value data = collectionsDb.get(key);
            if (data != null) {
                nextCollectionId = ByteConversion.byteToShort(data.getData(), 0);
                ++nextCollectionId;
            }
            byte[] d = new byte[2];
            ByteConversion.shortToByte(nextCollectionId, d, 0);
            collectionsDb.put(transaction, key, d, true);
            return nextCollectionId;
        } catch (LockException e) {
            LOG.warn("Failed to acquire lock on " + collectionsDb.getFile().getName(), e);
            return Collection.UNKNOWN_COLLECTION_ID;
            //TODO : rethrow ? -pb
        } finally {
            lock.release();
        }       
    }
    
    public void reindexCollection(XmldbURI collectionName) throws PermissionDeniedException {
        if (readOnly)
            throw new PermissionDeniedException(DATABASE_IS_READ_ONLY);
        collectionName=prepend(collectionName.toCollectionPathURI());
        
        Collection collection = getCollection(collectionName);
        if (collection == null) {
            LOG.debug("collection " + collectionName + " not found!");
            return;
        }
        reindexCollection(collection, false);
    }    
    
    public void reindexCollection(Collection collection, boolean repairMode) throws PermissionDeniedException {
        TransactionManager transact = pool.getTransactionManager();
        Txn transaction = transact.beginTransaction();
        try {
            reindexCollection(transaction, collection, repairMode);
            transact.commit(transaction);
        } catch (TransactionException e) {
            transact.abort(transaction);
            LOG.warn("An error occurred during reindex: " + e.getMessage(), e);
        }
    }
    
    public void reindexCollection(Txn transaction, Collection collection, boolean repairMode) throws PermissionDeniedException {
        if (!collection.getPermissions().validate(user, Permission.WRITE))
            throw new PermissionDeniedException("insufficient privileges on collection " + collection.getURI());
        LOG.debug("Reindexing collection " + collection.getURI());
        
        if (!repairMode)
            dropCollectionIndex(collection);
        for(Iterator i = collection.iterator(this); i.hasNext(); ) {
            DocumentImpl next = (DocumentImpl)i.next();
            reindexXMLResource(transaction, next, repairMode);
        }
        for(Iterator i = collection.collectionIterator(); i.hasNext(); ) {
        	XmldbURI next = (XmldbURI)i.next();
            Collection child = getCollection(collection.getURI().append(next));
            if(child == null)
                LOG.warn("Collection '" + next + "' not found");
            else {
                reindexCollection(transaction, child, repairMode);
            }
        }
    }
    
    public void dropCollectionIndex(Collection collection) throws PermissionDeniedException {
        if (readOnly)
            throw new PermissionDeniedException(DATABASE_IS_READ_ONLY);
        if (!collection.getPermissions().validate(user, Permission.WRITE))
            throw new PermissionDeniedException("insufficient privileges on collection " + 
                    collection.getURI());
        
        notifyDropIndex(collection);
        
        for (Iterator i = collection.iterator(this); i.hasNext();) {
            final DocumentImpl doc = (DocumentImpl) i.next();
            LOG.debug("Dropping index for document " + doc.getFileURI());
            new DOMTransaction(this, domDb, Lock.WRITE_LOCK) {
                public Object start() {
                    try {
                        Value ref = new NodeRef(doc.getDocId());
                        IndexQuery query =
                            new IndexQuery(IndexQuery.TRUNC_RIGHT, ref);
                        domDb.remove(query, null);
                        domDb.flush();
                    } catch (BTreeException e) {
                        LOG.warn("btree error while removing document", e);
                    } catch (DBException e) {
                        LOG.warn("db error while removing document", e);
                    } catch (IOException e) {
                        LOG.warn("io error while removing document", e);
                    } catch (TerminatedException e) {
                        LOG.warn("method terminated", e);
                    }
                    return null;
                }
            }
            .run();
        }
    }    
    
    /** store into the temporary collection of the database a given in-memory Document */
    public DocumentImpl storeTempResource(org.exist.memtree.DocumentImpl doc) 
    throws EXistException, PermissionDeniedException, LockException {
        TransactionManager transact = pool.getTransactionManager();
        Txn transaction = transact.beginTransaction();
        
        user = pool.getSecurityManager().getUser(SecurityManager.DBA_USER);
        XmldbURI docName = XmldbURI.create(MD5.md(Thread.currentThread().getName() + Long.toString(System.currentTimeMillis()),false) +
            ".xml");
        Collection temp = openCollection(XmldbURI.TEMP_COLLECTION_URI, Lock.WRITE_LOCK);
        
        try {
            if(temp == null)
                temp = createTempCollection(transaction);
            else
                transaction.registerLock(temp.getLock(), Lock.WRITE_LOCK);
            DocumentImpl targetDoc = new DocumentImpl(this, temp, docName);
            targetDoc.setPermissions(0771);
            long now = System.currentTimeMillis();
            DocumentMetadata metadata = new DocumentMetadata();
            metadata.setLastModified(now);
            metadata.setCreated(now);
            targetDoc.setMetadata(metadata);
            targetDoc.setDocId(getNextResourceId(transaction, temp));

            DOMIndexer indexer = new DOMIndexer(this, transaction, doc, targetDoc);
            indexer.scan();
            indexer.store();
            temp.addDocument(transaction, this, targetDoc);
            storeXMLResource(transaction, targetDoc);
            closeDocument();
            flush();
            transact.commit(transaction);
            return targetDoc;
        } catch (Exception e) {
            LOG.debug(e);
            transact.abort(transaction);
        }
        return null;
    }
    
    /** remove all documents from temporary collection */   
    public void cleanUpTempResources() {
        Collection temp = getCollection(XmldbURI.TEMP_COLLECTION_URI);
        if(temp == null)
            return;
        // remove the entire collection if all temp data has timed out
        boolean removeCollection = true;
        long now = System.currentTimeMillis();
        for(Iterator i = temp.iterator(this); i.hasNext(); ) {
            DocumentImpl next = (DocumentImpl) i.next();
            long modified = next.getMetadata().getLastModified();
            if(now - modified < TEMP_FRAGMENT_TIMEOUT) {
                removeCollection = false;
                break;
            }
        }
        
        if (removeCollection) {
            TransactionManager transact = pool.getTransactionManager();
            Txn txn = transact.beginTransaction();
            try {
                removeCollection(txn, temp);
                transact.commit(txn);
            } catch (TransactionException e) {
                transact.abort(txn);
                LOG.warn("Transaction aborted: " + e.getMessage(), e);
            } catch (PermissionDeniedException e) {
                transact.abort(txn);
                LOG.warn("Failed to remove temp collection: " + e.getMessage(), e);
            }
        }
    }
    
    /** remove from the temporary collection of the database a given list of Documents. */
    public void cleanUpTempResources(List docs) {
        Collection temp = openCollection(XmldbURI.TEMP_COLLECTION_URI, Lock.WRITE_LOCK);
        if(temp == null)
            return;
        TransactionManager transact = pool.getTransactionManager();
        Txn txn = transact.beginTransaction();
        txn.registerLock(temp.getLock(), Lock.WRITE_LOCK);
        try {
            for(Iterator i = docs.iterator(); i.hasNext(); )
                temp.removeXMLResource(txn, this, XmldbURI.create((String) i.next()));
            transact.commit(txn);
        } catch (PermissionDeniedException e) {
            transact.abort(txn);
            LOG.warn(TEMP_FRAGMENT_REMOVE_ERROR, e);
        } catch (TriggerException e) {
            transact.abort(txn);
            LOG.warn(TEMP_FRAGMENT_REMOVE_ERROR, e);
        } catch (LockException e) {
            transact.abort(txn);
            LOG.warn(TEMP_FRAGMENT_REMOVE_ERROR, e);
        } catch (TransactionException e) {
            transact.abort(txn);
            LOG.warn(TEMP_FRAGMENT_REMOVE_ERROR, e);
        }
    }
    
    /** store Document entry into its collection. */
    public void storeXMLResource(final Txn transaction, final DocumentImpl doc) {
        Lock lock = collectionsDb.getLock();
        try {
            lock.acquire();
            final VariableByteOutputStream ostream = new VariableByteOutputStream(8);
            doc.getMetadata().write(ostream);
            long metaPointer = collectionsDb.storeValue(transaction, ostream.data());
            ostream.clear();
            doc.setMetadataLocation(metaPointer);
            doc.write(ostream);
            Value key = new DocumentKey(doc.getCollection().getId(), doc.getResourceType(), doc.getDocId());
            collectionsDb.put(transaction, key, ostream.data(), true);
        } catch (ReadOnlyException e) {
            LOG.warn(DATABASE_IS_READ_ONLY);
        } catch (LockException e) {
            LOG.warn("Failed to acquire lock on " + collectionsDb.getFile().getName());
        } catch (IOException e) {
            LOG.warn("IOException while writing document data", e);
        } finally {
            lock.release();
        }
    }
    
    public void storeBinaryResource(final Txn transaction, final BinaryDocument blob, final byte[] data) {
    	if (data.length == 0) {
    		blob.setPage(-1);
    		return;
    	}
        new DOMTransaction(this, domDb, Lock.WRITE_LOCK) {
            public Object start() throws ReadOnlyException {
                LOG.debug("Storing binary resource " + blob.getFileURI());
                blob.setPage(domDb.addBinary(transaction, blob, data));
                return null;
            }
        }
        .run();
    }    
    
    public void storeBinaryResource(final Txn transaction, final BinaryDocument blob, final InputStream is) {
    	if (is == null) {
    		blob.setPage(-1);
    		return;
    	}
        new DOMTransaction(this, domDb, Lock.WRITE_LOCK) {
            public Object start() throws ReadOnlyException {
                LOG.debug("Storing binary resource as a stream " + blob.getFileURI());
                blob.setPage(domDb.addBinary(transaction, blob, is));
                return null;
            }
        }
        .run();
    }  
    
    
    /**
     *  get a document by its file name. The document's file name is used to
     *  identify a document.
     *
     *@param  fileName absolute file name in the database; 
     *name can be given with or without the leading path /db/shakespeare.
     *@return  The document value
     *@exception  PermissionDeniedException 
     */
    public Document getXMLResource(XmldbURI fileName) throws PermissionDeniedException {
        fileName = prepend(fileName.toCollectionPathURI());
        XmldbURI collUri = fileName.removeLastSegment();
        XmldbURI docUri = fileName.lastSegment();
         
        Collection collection = getCollection(collUri);
        if (collection == null) {
            LOG.debug("collection '" + collUri + "' not found!");
            return null;
        }
        if (!collection.getPermissions().validate(user, Permission.READ))
            throw new PermissionDeniedException("Permission denied to read collection '" + collUri + "'");
        
        DocumentImpl doc = collection.getDocument(this, docUri);
        if (doc == null) {
            LOG.debug("document '" + fileName + "' not found!");
            return null;
        }
        
//      if (!doc.getPermissions().validate(user, Permission.READ))
//          throw new PermissionDeniedException("not allowed to read document");
        
        return doc;
    }
    
    public DocumentImpl getXMLResource(XmldbURI fileName, int lockMode) throws PermissionDeniedException {
        fileName = prepend(fileName.toCollectionPathURI());
        XmldbURI collUri = fileName.removeLastSegment();
        XmldbURI docUri = fileName.lastSegment();
        
        Collection collection = openCollection(collUri, lockMode);
        if (collection == null) {
            LOG.debug("collection '" + collUri + "' not found!");
            return null;
        }
        if (!collection.getPermissions().validate(user, Permission.READ))
            throw new PermissionDeniedException("Permission denied to read collection '" + collUri + "'");
        
        try {
            DocumentImpl doc = collection.getDocumentWithLock(this, docUri, lockMode);
            if (doc == null) {
                LOG.debug("document '" + fileName + "' not found!");
                return null;
            }
            
    //      if (!doc.getPermissions().validate(user, Permission.READ))
    //          throw new PermissionDeniedException("not allowed to read document");
            
            return doc;
        } catch (LockException e) {
            LOG.warn("Could not acquire lock on document " + fileName, e);
            //TODO : exception ? -pb
        } finally {
            //TOUNDERSTAND : by whom is this lock acquired ? -pb
            if(collection != null)
                collection.release();            
        }
        return null;
    }    
    
    public byte[] getBinaryResource(final BinaryDocument blob) {
    	if (blob.getPage() < 0)
    		return new byte[0];
        byte[] data = (byte[]) new DOMTransaction(this, domDb, Lock.WRITE_LOCK) {
            public Object start() throws ReadOnlyException {
                return domDb.getBinary(blob.getPage());
            }
        }
        .run();
        return data;
    }
    
    public void readBinaryResource(final BinaryDocument blob, final OutputStream os) {
    	if (blob.getPage() < 0)
    		return;
        new DOMTransaction(this, domDb, Lock.WRITE_LOCK) {
            public Object start() throws ReadOnlyException {
                domDb.readBinary(blob.getPage(), os);
                return null;
            }
        }.run();
    }
    
    //TODO : consider a better cooperation with Collection -pb
    public void getCollectionResources(Collection collection) {
        Lock lock = collectionsDb.getLock();
        try {
            lock.acquire();            
            Value key = new DocumentKey(collection.getId());
            IndexQuery query = new IndexQuery(IndexQuery.TRUNC_RIGHT, key);
            collectionsDb.query(query, new DocumentCallback(collection));
        } catch (LockException e) {
            LOG.warn("Failed to acquire lock on " + collectionsDb.getFile().getName());
        } catch (IOException e) {
            LOG.warn("IOException while reading document data", e);
        } catch (BTreeException e) {
            LOG.warn("Exception while reading document data", e);
        } catch (TerminatedException e) {
            LOG.warn("Exception while reading document data", e);
        } finally {
            lock.release();
        }
    }
    
    /**
     *  get all the documents in this database matching the given
     *  document-type's name.
     *
     *@param  doctypeName  Description of the Parameter
     *@param  user         Description of the Parameter
     *@return              The documentsByDoctype value
     */
    public DocumentSet getXMLResourcesByDoctype(String doctypeName, DocumentSet result) {
        DocumentSet docs = getAllXMLResources(new DocumentSet());
        DocumentImpl doc;
        DocumentType doctype;
        for (Iterator i = docs.iterator(); i.hasNext();) {
            doc = (DocumentImpl) i.next();
            doctype = doc.getDoctype();
            if (doctype == null)
                continue;
            if (doctypeName.equals(doctype.getName())
                && doc.getCollection().getPermissions().validate(user, Permission.READ)
                && doc.getPermissions().validate(user, Permission.READ))
                result.add(doc);
        }
        return result;
    }
    
    /**
     *  Adds all the documents in the database to the specified DocumentSet.
     *
     * @param docs a (possibly empty) document set to which the found
     *  documents are added.
     */
    public DocumentSet getAllXMLResources(DocumentSet docs) {
        long start = System.currentTimeMillis();
        Collection root = null;
        try {
            root = openCollection(XmldbURI.ROOT_COLLECTION_URI, Lock.READ_LOCK);
            root.allDocs(this, docs, true, false);
            if (LOG.isDebugEnabled()) {
                LOG.debug("getAllDocuments(DocumentSet) - end - "
                        + "loading "
                        + docs.getLength()
                        + " documents from "
                        + docs.getCollectionCount()
                        + "collections took "
                        + (System.currentTimeMillis() - start)
                        + "ms.");
            }
            return docs;
        } finally {
            root.release();
        }
    }    
    
    //TODO : consider a better cooperation with Collection -pb
    public void getResourceMetadata(DocumentImpl doc) {
        Lock lock = collectionsDb.getLock();
        try {
            lock.acquire();
            SanityCheck.ASSERT(doc.getMetadataLocation() != StoredNode.UNKNOWN_NODE_IMPL_ADDRESS, 
                    "Missing pointer to metadata location in document " + doc.getDocId());
            VariableByteInput istream = collectionsDb.getAsStream(doc.getMetadataLocation());
            DocumentMetadata metadata = new DocumentMetadata();
            metadata.read(istream);
            doc.setMetadata(metadata);
        } catch (LockException e) {
            LOG.warn("Failed to acquire lock on " + collectionsDb.getFile().getName());
        } catch (IOException e) {
            LOG.warn("IOException while reading document data", e);
        } finally {
            lock.release();
        }
    }

    public void copyXMLResource(Txn transaction, DocumentImpl doc, Collection destination, XmldbURI newName) 
    throws PermissionDeniedException, LockException {
        if (readOnly)
            throw new PermissionDeniedException(DATABASE_IS_READ_ONLY);
        Collection collection = doc.getCollection();
        if(!collection.getPermissions().validate(user, Permission.READ))
            throw new PermissionDeniedException("Insufficient privileges to copy resource " +
                    doc.getFileURI());
        if(!doc.getPermissions().validate(user, Permission.READ))
            throw new PermissionDeniedException("Insufficient privileges to copy resource " +
                    doc.getFileURI());

        if(newName==null) {
        	newName = doc.getFileURI();
        }
        Lock lock = null;
        try {
            lock = collectionsDb.getLock();
            lock.acquire(Lock.WRITE_LOCK);
            // check if the move would overwrite a collection
            if(getCollection(destination.getURI().append(newName)) != null)
                throw new PermissionDeniedException("A resource can not replace an existing collection");
            DocumentImpl oldDoc = destination.getDocument(this, newName);
            if(oldDoc != null) {
                if(doc.getDocId() == oldDoc.getDocId())
                    throw new PermissionDeniedException("Cannot copy resource to itself");
                if(!destination.getPermissions().validate(user, Permission.UPDATE))
                    throw new PermissionDeniedException("Resource with same name exists in target " +
                            "collection and update is denied");
                if(!oldDoc.getPermissions().validate(user, Permission.UPDATE))
                    throw new PermissionDeniedException("Resource with same name exists in target " +
                            "collection and update is denied");
                if (oldDoc.getResourceType() == DocumentImpl.BINARY_FILE)
                    destination.removeBinaryResource(transaction, this, oldDoc);
                else
                    destination.removeXMLResource(transaction, this, oldDoc.getFileURI());
            } else {
                if(!destination.getPermissions().validate(user, Permission.WRITE))
                    throw new PermissionDeniedException("Insufficient privileges on target collection " +
                            destination.getURI());
            }
            if (doc.getResourceType() == DocumentImpl.BINARY_FILE)  {
                byte[] data = getBinaryResource((BinaryDocument) doc); 
                destination.addBinaryResource(transaction, this, newName, data, doc.getMetadata().getMimeType());
            } else {
                DocumentImpl newDoc = new DocumentImpl(this, destination, newName);
                newDoc.copyOf(doc);
                newDoc.setDocId(getNextResourceId(transaction, destination));
                newDoc.setPermissions(doc.getPermissions()); 
                copyXMLResource(transaction, doc, newDoc);
                destination.addDocument(transaction, this, newDoc);
                storeXMLResource(transaction, newDoc);
            }
//          saveCollection(destination);
        } catch (EXistException e) {
            LOG.warn("An error occurred while copying resource", e);
        } catch (TriggerException e) {
            throw new PermissionDeniedException(e.getMessage());
        } finally {
            lock.release();
        }
    }
    
    private void copyXMLResource(Txn transaction, DocumentImpl oldDoc, DocumentImpl newDoc) {
        LOG.debug("Copying document " + oldDoc.getFileURI() + " to " + 
                newDoc.getURI());
        final long start = System.currentTimeMillis();
        NodeList nodes = oldDoc.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
        	StoredNode node = (StoredNode) nodes.item(i);
        	Iterator iterator = getNodeIterator(node);
            iterator.next();
            copyNodes(transaction, iterator, node, new NodePath(), newDoc, true);
        }
        flush();
        closeDocument();
        LOG.debug("Copy took " + (System.currentTimeMillis() - start) + "ms.");
    }
    
    /** move Resource to another collection, with possible rename */
    public void moveXMLResource(Txn transaction, DocumentImpl doc, Collection destination, XmldbURI newName)
    throws PermissionDeniedException, LockException {
        if (readOnly)
            throw new PermissionDeniedException(DATABASE_IS_READ_ONLY);
        
        //TODO : somewhat inconsistent (READ is enough for original doc whereas WRITE is mandatory for destination) -pb
        Collection collection = doc.getCollection();                
        if(!collection.getPermissions().validate(user, Permission.WRITE))
            throw new PermissionDeniedException("Insufficient privileges to move resource " +
                    doc.getFileURI());
        if(!doc.getPermissions().validate(user, Permission.WRITE))
            throw new PermissionDeniedException("Insufficient privileges to move resource " +
                    doc.getFileURI());
      
        User docUser = doc.getUserLock();
        if (docUser != null) {
           if(!(user.getName()).equals(docUser.getName()))
                throw new PermissionDeniedException("Cannot move '" + doc.getFileURI() + 
                        " because is locked by user '" + docUser.getName() + "'");
        }
        
        if(newName==null) {
        	newName = doc.getFileURI();
        }
        Lock lock = collectionsDb.getLock();
        try {           
            lock.acquire(Lock.WRITE_LOCK);
            // check if the move would overwrite a collection
            if(getCollection(destination.getURI().append(newName)) != null)
                throw new PermissionDeniedException("A resource can not replace an existing collection");
            DocumentImpl oldDoc = destination.getDocument(this, newName);
            if(oldDoc != null) {
                if(doc.getDocId() == oldDoc.getDocId())
                    throw new PermissionDeniedException("Cannot move resource to itself");
                if(!destination.getPermissions().validate(user, Permission.UPDATE))
                    throw new PermissionDeniedException("Resource with same name exists in target " +
                            "collection and update is denied");
                if(!oldDoc.getPermissions().validate(user, Permission.UPDATE))
                    throw new PermissionDeniedException("Resource with same name exists in target " +
                            "collection and update is denied");
                if (oldDoc.getResourceType() == DocumentImpl.BINARY_FILE)
                    destination.removeBinaryResource(transaction, this, oldDoc);
                else
                    destination.removeXMLResource(transaction, this, oldDoc.getFileURI());
            } else
                if(!destination.getPermissions().validate(user, Permission.WRITE))
                    throw new PermissionDeniedException("Insufficient privileges on target collection " +
                            destination.getURI());
                
            boolean renameOnly = collection.getId() == destination.getId();
            collection.unlinkDocument(doc);
            removeResourceMetadata(transaction, doc);
            doc.setFileURI(newName);
            doc.setCollection(destination);
            if (doc.getResourceType() == DocumentImpl.XML_FILE) {
                if(!renameOnly) {
                    notifyDropIndex(doc);
                    saveCollection(transaction, collection);
                }
                destination.addDocument(transaction, this, doc);
    
                if(!renameOnly) {
                    // reindexing
                    reindexXMLResource(transaction, doc, false);
                }
            } else {
                // binary resource
                destination.addDocument(transaction, this, doc);
            }
            storeXMLResource(transaction, doc);
            saveCollection(transaction, destination);
        } catch (TriggerException e) {
            throw new PermissionDeniedException(e.getMessage());
        } catch (ReadOnlyException e) {
            throw new PermissionDeniedException(e.getMessage());
        } finally {
            lock.release();
        }
    }
    
    public void removeXMLResource(final Txn transaction, final DocumentImpl document, 
            boolean freeDocId) throws PermissionDeniedException {
        if (readOnly)
            throw new PermissionDeniedException(DATABASE_IS_READ_ONLY);
        try {
            if (LOG.isInfoEnabled()) {
                LOG.info("Removing document "
                    + document.getFileURI()
                    + " ...");
            }
            
            notifyDropIndex(document);
            if (LOG.isDebugEnabled()) {
                LOG.debug("removeDocument() - removing dom");
            }
            new DOMTransaction(this, domDb) {
                public Object start() {
                    StoredNode node = (StoredNode)document.getFirstChild();
                    domDb.removeAll(transaction, node.getInternalAddress());
                    return null;
                }
            }
            .run();
            
            NodeRef ref = new NodeRef(document.getDocId());
            final IndexQuery idx = new IndexQuery(IndexQuery.TRUNC_RIGHT, ref);
            new DOMTransaction(this, domDb) {
                public Object start() {
                    try {
                        domDb.remove(transaction, idx, null);
                    } catch (BTreeException e) {
                        LOG.warn("start() - " + "error while removing doc", e);                 
                    } catch (IOException e) {
                        LOG.warn("start() - " + "error while removing doc", e);
                    } catch (TerminatedException e) {
                        LOG.warn("method terminated", e);
                    }
                    return null;
                }
            }
            .run();
            
            removeResourceMetadata(transaction, document);
            
            if(freeDocId)
                freeResourceId(transaction, document.getDocId());
        } catch (ReadOnlyException e) {
            LOG.warn("removeDocument(String) - " + DATABASE_IS_READ_ONLY);
        }
    }
    
    
    public void removeBinaryResource(final Txn transaction, final BinaryDocument blob)
    throws PermissionDeniedException {
        if (readOnly)
            throw new PermissionDeniedException(DATABASE_IS_READ_ONLY);
        LOG.info("removing binary resource " + blob.getDocId() + "...");
        if (blob.getPage() > -1) {
	        new DOMTransaction(this, domDb, Lock.WRITE_LOCK) {
	            public Object start() throws ReadOnlyException {
	                domDb.removeOverflowValue(transaction, blob.getPage());
	                return null;
	            }
	        }
	        .run();
        }
        removeResourceMetadata(transaction, blob);
    }

    /**
     * @param transaction
     * @param document
     */
    private void removeResourceMetadata(final Txn transaction, final DocumentImpl document) {
        // remove document metadata
        Lock lock = collectionsDb.getLock();
        try {
            lock.acquire();            
            Value key = new DocumentKey(document.getCollection().getId(), document.getResourceType(), document.getDocId());
            collectionsDb.remove(transaction, key);
        } catch (ReadOnlyException e) {
            LOG.warn(DATABASE_IS_READ_ONLY);
        } catch (LockException e) {
            LOG.warn("Failed to acquire lock on " + collectionsDb.getFile().getName());
        } finally {
            lock.release();
        }
    }

	/**
	 * Release the document id reserved for a document so it
	 * can be reused.
	 * 
	 * @param id
	 * @throws PermissionDeniedException
	 */
	protected void freeResourceId(Txn transaction, int id) throws PermissionDeniedException {		
		Lock lock = collectionsDb.getLock();
		try {
			lock.acquire(Lock.WRITE_LOCK);
            Value key = new Value(CollectionStore.FREE_DOC_ID_KEY);
			Value value = collectionsDb.get(key);
			if (value != null) {
                byte[] data = value.getData();
				byte[] ndata = new byte[data.length + 4];
				System.arraycopy(data, 0, ndata, 4, data.length);
				ByteConversion.intToByte(id, ndata, 0);
				collectionsDb.put(transaction, key, ndata, true);
			} else {
				byte[] data = new byte[4];
				ByteConversion.intToByte(id, data, 0);
				collectionsDb.put(transaction, key, data, true);
			}
		} catch (LockException e) {
			LOG.warn("Failed to acquire lock on " + collectionsDb.getFile().getName(), e);
            //TODO : rethrow ? -pb
		} catch (ReadOnlyException e) {
		    throw new PermissionDeniedException(DATABASE_IS_READ_ONLY);
        } finally {
			lock.release();
		}
	}
	
	/**
	 * Get the next unused document id. If a document is removed, its doc id is
	 * released, so it can be reused.
	 * 
	 * @return
	 * @throws ReadOnlyException
	 */
	public int getFreeResourceId(Txn transaction) throws ReadOnlyException {
		int freeDocId = DocumentImpl.UNKNOWN_DOCUMENT_ID;		
		Lock lock = collectionsDb.getLock();
		try {
			lock.acquire(Lock.WRITE_LOCK);
            Value key = new Value(CollectionStore.FREE_DOC_ID_KEY);
			Value value = collectionsDb.get(key);
			if (value != null) {
				byte[] data = value.getData();
				freeDocId = ByteConversion.byteToInt(data, data.length - 4);
//				LOG.debug("reusing document id: " + freeDocId);
				if(data.length - 4 > 0) {
					byte[] ndata = new byte[data.length - 4];
					System.arraycopy(data, 0, ndata, 0, ndata.length);
					collectionsDb.put(transaction, key, ndata, true);
				} else
					collectionsDb.remove(transaction, key);
			}
            //TODO : maybe something ? -pb
		} catch (LockException e) {
			LOG.warn("Failed to acquire lock on " + collectionsDb.getFile().getName(), e);
			return DocumentImpl.UNKNOWN_DOCUMENT_ID;
            //TODO : rethrow ? -pb
		} finally {
			lock.release();
		}
		return freeDocId;
	}
	
    /** get next Free Doc Id */
	public int getNextResourceId(Txn transaction, Collection collection) {
		int nextDocId;
		try {
			nextDocId = getFreeResourceId(transaction);
		} catch (ReadOnlyException e) {
            //TODO : rethrow ? -pb
			return 1;
		}
		if (nextDocId != DocumentImpl.UNKNOWN_DOCUMENT_ID)
			return nextDocId;
		else
			nextDocId = 1;
		
		Lock lock = collectionsDb.getLock();
		try {
			lock.acquire(Lock.WRITE_LOCK);
            Value key = new Value(CollectionStore.NEXT_DOC_ID_KEY);
            Value data = collectionsDb.get(key);
			if (data != null) {
				nextDocId = ByteConversion.byteToInt(data.getData(), 0);
				++nextDocId;
			}
			byte[] d = new byte[4];
			ByteConversion.intToByte(nextDocId, d, 0);
			collectionsDb.put(transaction, key, d, true);
		} catch (ReadOnlyException e) {
			LOG.debug("database read-only");
			return DocumentImpl.UNKNOWN_DOCUMENT_ID;
            //TODO : rethrow ? -pb
		} catch (LockException e) {
			LOG.warn("Failed to acquire lock on " + collectionsDb.getFile().getName(), e);
            //TODO : rethrow ? -pb
		} finally {
			lock.release();
		}
		return nextDocId;
	}
    
    /**
     * Reindex the nodes in the document. This method will either reindex all
     * descendant nodes of the passed node, or all nodes below some level of
     * the document if node is null.
     */
    public void reindexXMLResource(final Txn transaction, final DocumentImpl oldDoc, final DocumentImpl doc, 
            final StoredNode node) {
        int idxLevel = doc.getMetadata().reindexRequired();     
        if (idxLevel == DocumentMetadata.REINDEX_ALL) {
            flush();
            return;
        }
        oldDoc.getMetadata().setReindexRequired(idxLevel);
        if (node == null)
            LOG.debug("reindexing level " + idxLevel + " of document " + doc.getDocId());
        //checkTree(doc);
        
        final long start = System.currentTimeMillis();
        //remove all old index keys from the btree 
        new DOMTransaction(this, domDb, Lock.WRITE_LOCK) {
            public Object start() throws ReadOnlyException {
                try {
                    Value ref = new NodeRef(doc.getDocId());
                    IndexQuery query = new IndexQuery(IndexQuery.TRUNC_RIGHT, ref);
                    final ArrayList nodes = domDb.findKeys(query);                  
                    for (Iterator i = nodes.iterator(); i.hasNext();) {
                        ref = (Value) i.next();
                        long gid = ByteConversion.byteToLong(ref.data(), ref.start() + 4);
                        if (oldDoc.getTreeLevel(gid) >= doc.getMetadata().reindexRequired()) {
                            if (node != null) {
                                if (NodeSetHelper.isDescendant(oldDoc, node.getGID(), gid)) {
                                    domDb.removeValue(transaction, ref);
                                }
                            } else
                                domDb.removeValue(transaction, ref);
                        }
                    }
                } catch (BTreeException e) {
                    LOG.debug("Exception while reindexing document: " + e.getMessage(), e);
                } catch (IOException e) {
                    LOG.debug("Exception while reindexing document: " + e.getMessage(), e);
                }
                return null;
            }
        }.run();
        try {
            // now reindex the nodes
            Iterator iterator;
            if (node == null) {
                NodeList nodes = doc.getChildNodes();                
                for (int i = 0; i < nodes.getLength(); i++) {
                    StoredNode n = (StoredNode) nodes.item(i);
                    iterator = getNodeIterator(n);
                    iterator.next();
                    scanNodes(transaction, iterator, n, new NodePath(), false, false);
                }
            } else {
                iterator = getNodeIterator(node);
                iterator.next();
                scanNodes(transaction, iterator, node, node.getPath(), false, false);
            }
        } catch(Exception e) {
            LOG.error("Error occured while reindexing document: " + e.getMessage(), e);
        }
        notifyReindex(oldDoc, node);
        doc.getMetadata().setReindexRequired(DocumentMetadata.REINDEX_ALL);
        //checkTree(doc);
        LOG.debug("reindex took " + (System.currentTimeMillis() - start) + "ms.");
    }
    
    /**
     * Reindex the nodes in the document. This method will either reindex all
     * descendant nodes of the passed node, or all nodes below some level of
     * the document if node is null.
     */
    private void reindexXMLResource(Txn transaction, DocumentImpl doc, boolean repairMode) {
        if(CollectionConfiguration.DEFAULT_COLLECTION_CONFIG_FILE.equals(doc.getFileURI()))
            doc.getCollection().setConfigEnabled(false);
        NodeList nodes = doc.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
        	StoredNode node = (StoredNode) nodes.item(i);
        	Iterator iterator = getNodeIterator(node);
            iterator.next();
            scanNodes(transaction, iterator, node, new NodePath(), true, repairMode);
        }
        flush();
        if(CollectionConfiguration.DEFAULT_COLLECTION_CONFIG_FILE.equals(doc.getFileURI()))
            doc.getCollection().setConfigEnabled(true);
    }  
    
    public void defragXMLResource(final Txn transaction, final DocumentImpl doc) {
        //TODO : use dedicated function in XmldbURI
        LOG.debug("============> Defragmenting document " + 
                doc.getCollection().getURI() + "/" + doc.getFileURI());
//        Writer writer = new StringWriter();
//        try {
//            domDb.dump(writer);
//        } catch (BTreeException e1) {
//            //  Auto-generated catch block
//            e1.printStackTrace();
//        } catch (IOException e1) {
//            //  Auto-generated catch block
//            e1.printStackTrace();
//        }
//        System.out.println(writer.toString());
        
        final long start = System.currentTimeMillis();
        try {
//          checkTree(doc);
//            try {
//                domDb.printFreeSpaceList();
//            } catch (IOException e1) {
//                // Auto-generated catch block
//                e1.printStackTrace();
//            }
            // remember this for later remove
            final long firstChild = doc.getFirstChildAddress();
                
            // dropping old structure index
            elementIndex.dropIndex(doc);
            valueIndex.dropIndex(doc);
            if (qnameValueIndex != null)
                qnameValueIndex.dropIndex(doc);
            
            // dropping dom index
            NodeRef ref = new NodeRef(doc.getDocId());
            final IndexQuery idx = new IndexQuery(IndexQuery.TRUNC_RIGHT, ref);
            new DOMTransaction(this, domDb) {
                public Object start() {
                    try {
                        domDb.remove(transaction, idx, null);
                        domDb.flush();
                    } catch (BTreeException e) {
                        LOG.warn("start() - " + "error while removing doc", e);
                    } catch (IOException e) {
                        LOG.warn("start() - " + "error while removing doc", e);
                    } catch (TerminatedException e) {
                        LOG.warn("method terminated", e);
                    } catch (DBException e) {
                        LOG.warn("start() - " + "error while removing doc", e);
                    }
                    return null;
                }
            }
            .run();
            
            // create a copy of the old doc to copy the nodes into it
            DocumentImpl tempDoc = new DocumentImpl(this, doc.getCollection(), doc.getFileURI());
            tempDoc.copyOf(doc);
            tempDoc.setDocId(doc.getDocId());
            
            // copy the nodes
            NodeList nodes = doc.getChildNodes();
            for (int i = 0; i < nodes.getLength(); i++) {
            	StoredNode node = (StoredNode) nodes.item(i);
            	Iterator iterator = getNodeIterator(node);
                iterator.next();                
                copyNodes(transaction, iterator, node, new NodePath(), tempDoc, false);
            }
            flush();            
            // checkTree(tempDoc);
            // remove the old nodes
            new DOMTransaction(this, domDb) {
                public Object start() {
                    domDb.removeAll(transaction, firstChild);
                    try {
                        domDb.flush();
                    } catch (DBException e) {
                        LOG.warn("start() - " + "error while removing doc", e);
                    }
                    return null;
                }
            }
            .run();
            
            // checkTree(tempDoc);            
            doc.copyChildren(tempDoc);
            doc.getMetadata().setSplitCount(0);
            doc.getMetadata().setPageCount(tempDoc.getMetadata().getPageCount());
            
            storeXMLResource(transaction, doc);
            //Commented out since DocmentImpl has no more internal address
            //LOG.debug("new doc address = " + StorageAddress.toString(doc.getInternalAddress()));
            closeDocument();
//          new DOMTransaction(this, domDb, Lock.READ_LOCK) {
//              public Object start() throws ReadOnlyException {
//                  LOG.debug("Pages used: " + domDb.debugPages(doc));
//                  return null;
//              }
//          }.run();
            storeXMLResource(transaction, doc);
//            checkTree(doc);
            LOG.debug("Defragmentation took " + (System.currentTimeMillis() - start) + "ms.");
        } catch (ReadOnlyException e) {
            LOG.warn(DATABASE_IS_READ_ONLY, e);
        }
    }        
    
    /** consistency Check of the database; useful after XUpdates;
     * called if xupdate.consistency-checks is true in configuration */ 
    public void checkXMLResourceConsistency(DocumentImpl doc) throws EXistException {
        if(xupdateConsistencyChecks) {
            LOG.debug("Checking document " + doc.getFileURI());
            checkXMLResourceTree(doc);
//          elementIndex.consistencyCheck(doc);
        }
    }
    
    /** consistency Check of the database; useful after XUpdates;
     * called by {@link #checkResourceConsistency()} */
    public void checkXMLResourceTree(final DocumentImpl doc) {
        LOG.debug("Checking DOM tree for document " + doc.getFileURI());
        if(xupdateConsistencyChecks) {
            new DOMTransaction(this, domDb, Lock.READ_LOCK) {
                public Object start() throws ReadOnlyException {
                    LOG.debug("Pages used: " + domDb.debugPages(doc, false));
                    return null;
                }
            }.run();
            
            NodeList nodes = doc.getChildNodes();            
            for (int i = 0; i < nodes.getLength(); i++) {
            	StoredNode node = (StoredNode) nodes.item(i);
                Iterator iterator = getNodeIterator(node);
                iterator.next();
                checkNodeTree(iterator, node);
            }
            NodeRef ref = new NodeRef(doc.getDocId());
            final IndexQuery idx = new IndexQuery(IndexQuery.TRUNC_RIGHT, ref);
            new DOMTransaction(this, domDb) {
                public Object start() {
                    try {
                        domDb.findKeys(idx);
                    } catch (BTreeException e) {
                        LOG.warn("start() - " + "error while removing doc", e);
                    } catch (IOException e) {
                        LOG.warn("start() - " + "error while removing doc", e);
                    }
                    return null;
                }
            }
            .run();
        }
    }
    
    /**
     *  Store a node into the database. This method is called by the parser to
     *  write a node to the storage backend.
     *
     *@param  node         the node to be stored
     *@param  currentPath  path expression which points to this node's
     *      element-parent or to itself if it is an element (currently used by
     *      the Broker to determine if a node's content should be
     *      fulltext-indexed).  @param index switch to activate fulltext indexation
     */
    public void storeNode(final Txn transaction, final StoredNode node, NodePath currentPath, boolean fullTextIndex) {
        checkAvailableMemory();
        
        final DocumentImpl doc = (DocumentImpl)node.getOwnerDocument();
//        final boolean isTemp = TEMP_COLLECTION.equals(doc.getCollection().getName());
        final IndexSpec idxSpec = doc.getCollection().getIdxConf(this);
//        final FulltextIndexSpec ftIdx = idxSpec != null ? idxSpec.getFulltextIndexSpec() : null;
        final long gid = node.getGID();        
        if (gid < 0) {
            LOG.debug("illegal node: " + gid + "; " + node.getNodeName());
            Thread.dumpStack();
            return;
        }
        final short nodeType = node.getNodeType();
        final int depth = idxSpec == null ? defaultIndexDepth : idxSpec.getIndexDepth();
        new DOMTransaction(this, domDb, Lock.WRITE_LOCK, doc) {
            public Object start() throws ReadOnlyException {
                long address = BFile.UNKNOWN_ADDRESS;
                final byte data[] = node.serialize();
                if (nodeType == Node.TEXT_NODE
                    || nodeType == Node.ATTRIBUTE_NODE
                    || doc.getTreeLevel(gid) > depth)
                    address = domDb.add(transaction, data);
                else {
                    address = domDb.put(transaction, new NodeRef(doc.getDocId(), gid), data);
                }
                if (address == BFile.UNKNOWN_ADDRESS)
                    LOG.warn("address is missing");
                //TODO : how can we continue here ? -pb
                node.setInternalAddress(address);
                ByteArrayPool.releaseByteArray(data);
                return null;
            }
        }
        .run();
        ++nodesCount;

        nodeProcessor.reset(transaction, node, currentPath, fullTextIndex);
        nodeProcessor.doIndex();
    }
    
    public void updateNode(final Txn transaction, final StoredNode node) {
        try {
            final DocumentImpl doc = (DocumentImpl)node.getOwnerDocument();
            final long internalAddress = node.getInternalAddress();
            final byte[] data = node.serialize();
            new DOMTransaction(this, domDb, Lock.WRITE_LOCK) {
                public Object start() throws ReadOnlyException {
                    if (internalAddress != BFile.UNKNOWN_ADDRESS)
                        domDb.update(transaction, internalAddress, data);
                    else {
                        domDb.update(transaction, new NodeRef(doc.getDocId(), node.getGID()), data);
                    }
                    return null;
                }
            }
            .run();
            ByteArrayPool.releaseByteArray(data);
        } catch (Exception e) {
            Value oldVal = domDb.get(node.getInternalAddress());
            StoredNode old = 
                StoredNode.deserialize(oldVal.data(), oldVal.start(), oldVal.getLength(), 
                		(DocumentImpl)node.getOwnerDocument(), false);
            LOG.debug(
                "Exception while storing "
                    + node.getNodeName()
                    + "; gid = "
                    + node.getGID()
                    + "; old = " + old.getNodeName(),
                e);
        }
    }

    /**
     * Physically insert a node into the DOM storage.
     */
    public void insertNodeAfter(final Txn transaction, final StoredNode previous, final StoredNode node) {
        final byte data[] = node.serialize();
        final DocumentImpl doc = (DocumentImpl)previous.getOwnerDocument();
        new DOMTransaction(this, domDb, Lock.WRITE_LOCK, doc) {
            public Object start() {
                long address = previous.getInternalAddress();
                if (address != BFile.UNKNOWN_ADDRESS) {
                    address = domDb.insertAfter(transaction, doc, address, data);
                } else {
                    NodeRef ref = new NodeRef(doc.getDocId(), previous.getGID());
                    address = domDb.insertAfter(transaction, doc, ref, data);
                }
                node.setInternalAddress(address);
                return null;
            }
        }
        .run();
    }
    
    private void copyNodes(Txn transaction, Iterator iterator, StoredNode node, NodePath currentPath, 
            DocumentImpl newDoc, boolean fullTextIndex) {
        if (node.getNodeType() == Node.ELEMENT_NODE)
            currentPath.addComponent(node.getQName());
        final DocumentImpl doc = (DocumentImpl)node.getOwnerDocument();
        final long oldAddress = node.getInternalAddress();
        node.setOwnerDocument(newDoc);
        node.setInternalAddress(BFile.UNKNOWN_ADDRESS);
        storeNode(transaction, node, currentPath, fullTextIndex);
        if (node.getNodeType() == Node.ELEMENT_NODE)
            endElement(node, currentPath, null, oldAddress);
        if (node.getGID() == NodeProxy.DOCUMENT_ELEMENT_GID) {
            newDoc.appendChild(node);
        }
        node.setOwnerDocument(doc);
        
        if (node.hasChildNodes()) {
            final long firstChildId = NodeSetHelper.getFirstChildId(doc, node.getGID());            
            if (firstChildId < 0) {
                LOG.fatal(
                    "no child found: expected = "
                        + node.getChildCount()
                        + "; node = "
                        + node.getNodeName()
                        + "; gid = "
                        + node.getGID());
                throw new IllegalStateException("Wrong node id");
            }
            final long lastChildId = firstChildId + node.getChildCount();
            StoredNode child;
            for (long gid = firstChildId; gid < lastChildId; gid++) {
                child = (StoredNode) iterator.next();
                if(child == null) {
                    LOG.fatal("child " + gid + " not found for node: " + node.getNodeName() +
                            "; last = " + lastChildId + "; children = " + node.getChildCount());
                    throw new IllegalStateException("Wrong node id");
                }
                child.setGID(gid);
                copyNodes(transaction, iterator, child, currentPath, newDoc, fullTextIndex);
            }
        }
        if(node.getNodeType() == Node.ELEMENT_NODE) {
            currentPath.removeLastComponent();
        }
    }
    
    /** Removes the Node Reference from the database.
     * The index will be updated later, i.e. after all nodes have been physically 
     * removed. See {@link #endRemove()}. 
     * removeNode() just adds the node ids to the list in elementIndex 
     * for later removal.
     */
    public void removeNode(final Txn transaction, final StoredNode node, NodePath currentPath, String content) {
        final DocumentImpl doc = (DocumentImpl)node.getOwnerDocument();
        final IndexSpec idxSpec = doc.getCollection().getIdxConf(this);
        final FulltextIndexSpec ftIdx = idxSpec != null ? idxSpec.getFulltextIndexSpec() : null;

        new DOMTransaction(this, domDb, Lock.WRITE_LOCK, doc) {
            public Object start() {
                final long address = node.getInternalAddress();
                if (address != BFile.UNKNOWN_ADDRESS)
                    domDb.remove(transaction, new NodeRef(doc.getDocId(), node.getGID()), address);
                else
                    domDb.remove(transaction, new NodeRef(doc.getDocId(), node.getGID()));
                return null;
            }
        }
        .run();
        
        NodeProxy p = new NodeProxy(node);

        QName qname;
        switch (node.getNodeType()) {
            case Node.ELEMENT_NODE :
                // save element by calling ElementIndex
                qname = node.getQName();
                qname.setNameType(ElementValue.ELEMENT);
                elementIndex.setDocument(doc);
                elementIndex.addNode(qname, p);
                
                if (idxSpec != null) {
                    GeneralRangeIndexSpec spec = idxSpec.getIndexByPath(currentPath);
                    if(spec != null) {
                        valueIndex.setDocument(doc);
                        valueIndex.storeElement(spec.getType(), (ElementImpl) node, content);
                    }
                }               
                // qnameValueIndex.removeElement((ElementImpl) node, currentPath, content);
                notifyRemoveElement( (ElementImpl) node, currentPath, content );
                break;
                
            case Node.ATTRIBUTE_NODE :
                currentPath.addComponent(node.getQName());
                elementIndex.setDocument(doc);
                qname = node.getQName();
                qname.setNameType(ElementValue.ATTRIBUTE);
                elementIndex.addNode(qname, p);
                
                // check if attribute value should be fulltext-indexed
                // by calling IndexPaths.match(path) 
                boolean indexAttribs = true;
                if(ftIdx != null) {
                    indexAttribs = ftIdx.matchAttribute(currentPath);
                }
                if (indexAttribs)
                    textEngine.storeAttribute(ftIdx, (AttrImpl) node);
                if (idxSpec != null) {
                    GeneralRangeIndexSpec spec = idxSpec.getIndexByPath(currentPath);
                    if(spec != null) {
                        valueIndex.setDocument(doc);
                        valueIndex.storeAttribute(spec, (AttrImpl) node);
                    }                   
//                  RangeIndexSpec qnIdx = idxSpec.getIndexByQName(idxQName);
//                  if (qnIdx != null && qnameValueIndexation) {
//                      qnameValueIndex.setDocument(doc);
//                      qnameValueIndex.storeAttribute(qnIdx, (AttrImpl) node);
//                  }
                }
                
                if (qnameValueIndex != null)
                    qnameValueIndex.removeAttribute( (AttrImpl)node, currentPath, true);
                // qnameValueIndex.storeAttribute( (AttrImpl)node, currentPath, true);

                
                // if the attribute has type ID, store the ID-value
                // to the element index as well
                if (((AttrImpl) node).getType() == AttrImpl.ID) {
                    qname = new QName(((AttrImpl) node).getValue(), "", null);
                    qname.setNameType(ElementValue.ATTRIBUTE_ID);
                    elementIndex.addNode(qname, p);
                }
                currentPath.removeLastComponent();
                break;
            case Node.TEXT_NODE :
                // check if this textual content should be fulltext-indexed
                // by calling IndexPaths.match(path)
                if (ftIdx == null || ftIdx.match(currentPath)){
                    boolean valore = (ftIdx == null ? false : ftIdx.preserveContent(currentPath));
                    textEngine.storeText(ftIdx, (TextImpl) node, valore);
                }
                break;
        }
    }    

    public void removeAllNodes(Txn transaction, StoredNode node, NodePath currentPath) {
        Iterator iterator = getNodeIterator(node);
        iterator.next();
        Stack stack = new Stack();
        collectNodesForRemoval(stack, iterator, node, currentPath);
        RemovedNode next;
        while (!stack.isEmpty()) {
            next = (RemovedNode) stack.pop();
            removeNode(transaction, next.node, next.path, next.content);
        }
    }
    
    private void collectNodesForRemoval(Stack stack, Iterator iterator, StoredNode node, NodePath currentPath) {
        RemovedNode removed;
        switch (node.getNodeType()) {
            case Node.ELEMENT_NODE:
                DocumentImpl doc = (DocumentImpl)node.getOwnerDocument();
                String content = null;
                IndexSpec idxSpec =  doc.getCollection().getIdxConf(this);
                if (idxSpec != null) {
                    GeneralRangeIndexSpec spec = idxSpec.getIndexByPath(currentPath);
                    RangeIndexSpec qnIdx = idxSpec.getIndexByQName(node.getQName());
                    if (spec != null || qnIdx != null) {
                        content = getNodeValue(node, false);
                    }
                }
                removed = new RemovedNode(node, new NodePath(currentPath), content);
                stack.push(removed);

                if (node.hasChildNodes()) {
                    final long firstChildId = NodeSetHelper.getFirstChildId(doc, node.getGID());                    
                    if (firstChildId < 0) {
                        LOG.fatal(
                            "no child found: expected = "
                                + node.getChildCount()
                                + "; node = "
                                + node.getNodeName()
                                + "; gid = "
                                + node.getGID());
                        throw new IllegalStateException("Wrong node id");
                    }
                    final long lastChildId = firstChildId + node.getChildCount();
                    StoredNode child;
                    for (long gid = firstChildId; gid < lastChildId; gid++) {
                        child = (StoredNode) iterator.next();
                        if(child == null) {
                            LOG.fatal("child " + gid + " not found for node: " + node.getNodeName() +
                                    "; last = " + lastChildId + "; children = " + node.getChildCount());
                            throw new IllegalStateException("Wrong node id");
                        }
                        child.setGID(gid);
                        if (child.getNodeType() == Node.ELEMENT_NODE)
                            currentPath.addComponent(((ElementImpl) child).getQName());
                        collectNodesForRemoval(stack, iterator, child, currentPath);
                        if (child.getNodeType() == Node.ELEMENT_NODE)
                            currentPath.removeLastComponent();
                    }
                }
                break;
            default :
                removed = new RemovedNode(node, new NodePath(currentPath), null);
                stack.push(removed);
                break;
        }
    }
    
    /**
     * Index a single node, which has been added through an XUpdate
     * operation. This method is only called if inserting the node is possible
     * without changing the node identifiers of sibling or parent nodes. In other 
     * cases, reindex will be called.
     */
    public void indexNode(Txn transaction, StoredNode node, NodePath currentPath) {
        indexNode(transaction, node, currentPath, false);
    }
    
    public void indexNode(final Txn transaction, final StoredNode node, NodePath currentPath, boolean repairMode) {
        nodeProcessor.reset(transaction, node, currentPath);
        nodeProcessor.index();
    }
    
    /**
     * Reindex the given node after the DOM tree has been 
     * modified by an XUpdate.
     * 
     * @param node
     * @param currentPath
     */
    private void reindexNode(final Txn transaction, final StoredNode node, NodePath currentPath) {
        nodeProcessor.reset(transaction, node, currentPath);
        nodeProcessor.reindex();
    }
    
    private void checkNodeTree(Iterator iterator, StoredNode node) {
        if (node.hasChildNodes()) {
            final long firstChildId = NodeSetHelper.getFirstChildId(
            		(DocumentImpl)node.getOwnerDocument(), node.getGID());          
            if (firstChildId < 0) {
                LOG.fatal(
                    "no child found: expected = "
                        + node.getChildCount()
                        + "; node = "
                        + node.getNodeName()
                        + "; gid = "
                        + node.getGID());
                throw new IllegalStateException("Wrong node id");
            }
            final long lastChildId = firstChildId + node.getChildCount();
            StoredNode child;
            for (long gid = firstChildId; gid < lastChildId; gid++) {
                child = (StoredNode) iterator.next();
                if(child == null) {
                    LOG.fatal("child " + gid + " not found for node: " + node.getNodeName() +
                            "; last = " + lastChildId + "; children = " + node.getChildCount());
                    throw new IllegalStateException("Wrong node id");
                }
                child.setGID(gid);
                checkNodeTree(iterator, child);
            }
        }
    } 
    
    /**
     * Called by reindex to walk through all nodes in the tree and reindex them
     * if necessary.
     * 
     * @param iterator
     * @param node
     * @param currentPath
     */
    private void scanNodes(Txn transaction, Iterator iterator, StoredNode node, NodePath currentPath,
            boolean fullReindex, boolean repairMode) {
        if (node.getNodeType() == Node.ELEMENT_NODE)
            currentPath.addComponent(node.getQName());
        if(fullReindex)
            indexNode(transaction, node, currentPath, repairMode);
        else
            reindexNode(transaction, node, currentPath);
        final DocumentImpl doc = (DocumentImpl)node.getOwnerDocument();
        if (node.hasChildNodes()) {
            final long firstChildId = NodeSetHelper.getFirstChildId(doc, node.getGID());            
            if (firstChildId < 0) {
                LOG.fatal(
                    "no child found: expected = "
                        + node.getChildCount()
                        + "; node = "
                        + node.getNodeName()
                        + "; gid = "
                        + node.getGID());
                throw new IllegalStateException("Wrong node id");
            }
            final long lastChildId = firstChildId + node.getChildCount();
            StoredNode child;
            for (long gid = firstChildId; gid < lastChildId; gid++) {
                child = (StoredNode) iterator.next();
                if(child == null) {
                    LOG.fatal("child " + gid + " not found for node: " + node.getNodeName() +
                            "; last = " + lastChildId + "; children = " + node.getChildCount());
                    throw new IllegalStateException("Wrong node id");
                }
                child.setGID(gid);
                scanNodes(transaction, iterator, child, currentPath, fullReindex, repairMode);
            }
        }
        if (node.getNodeType() == Node.ELEMENT_NODE) {
            if((fullReindex || doc.getTreeLevel(node.getGID()) >= doc.getMetadata().reindexRequired())) {
                endElement(node, currentPath, null);
            }
            currentPath.removeLastComponent();
        }
    }
    
    /**
     *  Do a sequential search through the DOM-file.
     *
     *@param  context     Description of the Parameter
     *@param  doc         Description of the Parameter
     *@param  relation    Description of the Parameter
     *@param  truncation  Description of the Parameter
     *@param  expr        Description of the Parameter
     *@return             Description of the Return Value
     */
    protected NodeSet scanNodesSequential(NodeSet context, DocumentSet doc, 
            int relation, int truncation, String expr, Collator collator) {
        ExtArrayNodeSet resultNodeSet = new ExtArrayNodeSet();        
        Pattern regexp = null;        
        if (relation == Constants.REGEXP) {
                regexp = Pattern.compile(expr.toLowerCase(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
                truncation = Constants.REGEXP;
        }
        for (Iterator i = context.iterator(); i.hasNext();) {
            NodeProxy p = (NodeProxy) i.next();
            String content;
            try {
                domDb.getLock().acquire(Lock.READ_LOCK);
                domDb.setOwnerObject(this);                
                content = domDb.getNodeValue(new StoredNode(p), false);
            } catch (LockException e) {
            	LOG.warn("Failed to acquire read lock on " + domDb.getFile().getName());
                continue;
            } finally {
                domDb.getLock().release();
            }
            String cmp;
            if (isCaseSensitive())
                cmp = StringValue.collapseWhitespace(content);
            else {
                cmp = StringValue.collapseWhitespace(content.toLowerCase());
            }
            switch (truncation) {
                case Constants.TRUNC_LEFT :
                    if (Collations.endsWith(collator, cmp, expr))
                        resultNodeSet.add(p);
                    break;
                case Constants.TRUNC_RIGHT :
                    if (Collations.startsWith(collator, cmp, expr))
                        resultNodeSet.add(p);
                    break;
                case Constants.TRUNC_BOTH :
                    if (Collations.indexOf(collator, cmp, expr) != Constants.STRING_NOT_FOUND)
                        resultNodeSet.add(p);
                    break;
                case Constants.TRUNC_NONE :             
                    int result = Collations.compare(collator, cmp, expr);
                    switch (relation) {
                        case Constants.LT :
                            if (result < 0)
                                resultNodeSet.add(p);
                            break;
                        case Constants.LTEQ :
                            if (result <= 0)
                                resultNodeSet.add(p);
                            break;
                        case Constants.GT :
                            if (result > 0)
                                resultNodeSet.add(p);
                            break;
                        case Constants.GTEQ :
                            if (result >= 0)
                                resultNodeSet.add(p);
                            break;
                        case Constants.EQ :
                            if (result == Constants.EQUAL)
                                resultNodeSet.add(p);
                            break;
                        case Constants.NEQ :
                            if (result != Constants.EQUAL)
                                resultNodeSet.add(p);
                            break;
                        default:
                            throw new IllegalArgumentException("Illegal argument 'relation': " + relation);
                    }
                    break;
                case Constants.REGEXP :
                    Matcher matcher = regexp.matcher(cmp);
                    if (regexp != null && matcher.find()) {
                        resultNodeSet.add(p);
                    }
                    break;
            }
        }
        return resultNodeSet;
    } 
	
	public String getNodeValue(final StoredNode node, final boolean addWhitespace) {
		return (String) new DOMTransaction(this, domDb, Lock.READ_LOCK) {
			public Object start() {
				return domDb.getNodeValue(node, addWhitespace);
			}
		}
		.run();
	}

	public NodeSet getNodesEqualTo(NodeSet context, DocumentSet docs, 
            int relation, int truncation, String expr, Collator collator) {
		if (!isCaseSensitive())
			expr = expr.toLowerCase();
		return scanNodesSequential(context, docs, relation, truncation, expr, collator);		
	}

    public NodeList getNodeRange(final Document doc, final long first, final long last) {
		NodeListImpl result = new NodeListImpl((int) (last - first + 1));
		for (long gid = first; gid <= last; gid++) {
			result.add(objectWith(doc, gid));
		}
		return result;
	}
    
    public StoredNode objectWith(final Document doc, final long gid) {    
		return (StoredNode) new DOMTransaction(this, domDb) {
			public Object start() {
				Value val = domDb.get(new NodeProxy((DocumentImpl) doc, gid));
				if (val == null)
					return null;				
				StoredNode node = StoredNode.deserialize(val.getData(),	0, val.getLength(),	(DocumentImpl) doc);
				node.setGID(gid);
				node.setOwnerDocument((DocumentImpl)doc);
				node.setInternalAddress(val.getAddress());
				return node;
			}
		}
		.run();
	}

	public StoredNode objectWith(final NodeProxy p) {       
		if (p.getInternalAddress() == StoredNode.UNKNOWN_NODE_IMPL_ADDRESS)
			return objectWith(p.getOwnerDocument(), p.getGID());
		return (StoredNode) new DOMTransaction(this, domDb) {
			public Object start() {
				Value val = domDb.get(p.getInternalAddress());
				if (val == null) {
					LOG.debug("Node " + p.getGID() + " not found in document " + ((DocumentImpl)p.getOwnerDocument()).getURI() +
							"; docId = " + ((DocumentImpl)p.getOwnerDocument()).getDocId());
//					LOG.debug(domDb.debugPages(p.doc, true));
//					return null;
					return objectWith(p.getOwnerDocument(), p.getGID()); // retry?
				}
				StoredNode node = StoredNode.deserialize(val.getData(), 0, val.getLength(), 
                        (DocumentImpl) p.getOwnerDocument());
				node.setGID(p.getGID());
				node.setOwnerDocument((DocumentImpl)p.getOwnerDocument());
				node.setInternalAddress(p.getInternalAddress());
				return node;
			}
		}
		.run();
	}
	
    public void repair() throws PermissionDeniedException {
        Collection root = getCollection(XmldbURI.ROOT_COLLECTION_URI);
        if (readOnly)
            throw new PermissionDeniedException(DATABASE_IS_READ_ONLY);
        
        LOG.debug("Removing index files ...");
        clearContentLoadingObservers();
        
        elementsDb.closeAndRemove();
        config.setProperty("db-connection.elements", null);
        
        dbWords.closeAndRemove();
        config.setProperty("db-connection.words", null);
        
        valuesDb.closeAndRemove();
        config.setProperty("db-connection.values", null);
        
        if (qnameValueIndexation) {
            valuesDbQname.closeAndRemove();
            config.setProperty("db-connection2.values", null);
        }        
        
        LOG.debug("Recreating index files ...");
        try {
            createIndexFiles();
        } catch (DBException e) {
            LOG.warn("Exception during repair: " + e.getMessage(), e);
        }
        LOG.info("Reindexing database files ...");
        reindexCollection(null, root, true);
    }
    
    public void flush() {
        notifyFlush();
        if (symbols != null && symbols.hasChanged())
            try {
                saveSymbols();
            } catch (EXistException e) {
                LOG.warn(e.getMessage(), e);
            }
        nodesCount = 0;
    } 
    
    public void sync(int syncEvent) {
        if (isReadOnly())
            return;
        try {
            new DOMTransaction(this, domDb, Lock.WRITE_LOCK) {
                public Object start() {
                    try {
                        domDb.flush();
                    } catch (DBException e) {
                        LOG.warn("error while flushing dom.dbx", e);
                    }
                    return null;
                }
            }
            .run();
            if(syncEvent == Sync.MAJOR_SYNC) {
                Lock lock = collectionsDb.getLock();
                try {
                    lock.acquire(Lock.WRITE_LOCK);
                    collectionsDb.flush();
                } catch (LockException e) {
                    LOG.warn("Failed to acquire lock on " + collectionsDb.getFile().getName(), e);
                } finally {
                    lock.release();
                }
                notifySync();
//              System.gc();
                Runtime runtime = Runtime.getRuntime();
                LOG.info("Memory: " + (runtime.totalMemory() / 1024) + "K total; " +
                        (runtime.maxMemory() / 1024) + "K max; " +
                        (runtime.freeMemory() / 1024) + "K free");              
                
                domDb.printStatistics(); 
                collectionsDb.printStatistics();
                if (elementsDb != null)
                    elementsDb.printStatistics();                
                if (valuesDb != null)
                    valuesDb.printStatistics();             
                if (valuesDbQname != null)
                    valuesDbQname.printStatistics();
                if (textEngine != null)
                    textEngine.printStatistics();
            }
        } catch (DBException dbe) {
            dbe.printStackTrace();
            LOG.debug(dbe);
        }
    }     

	public void shutdown() {		
		try {
			flush();
//            cleanUpTempCollection();
			sync(Sync.MAJOR_SYNC);
            domDb.close();
            textEngine.close();
            collectionsDb.close();
			elementsDb.close();
			valuesDb.close();
			if (qnameValueIndex != null)
				qnameValueIndex.close();			
		} catch (Exception e) {
			LOG.debug(e);
			e.printStackTrace();
		}
        super.shutdown();
	}    

    /** check available memory */
    private void checkAvailableMemory() {
        if (nodesCount > DEFAULT_NODES_BEFORE_MEMORY_CHECK) {
            final double percent = ((double) run.freeMemory() / (double) run.maxMemory()) * 100;
            if (percent < memMinFree) {
                //LOG.info(
                //  "total memory: " + run.totalMemory() + "; free: " + run.freeMemory());
                flush();
                System.gc();
                LOG.info(
                    "total memory: " + run.totalMemory() + "; free: " + run.freeMemory());
            }
        }
    }

	//TOUNDERSTAND : why not use shutdown ? -pb
	public void closeDocument() {
		new DOMTransaction(this, domDb, Lock.WRITE_LOCK) {
			public Object start() {
				domDb.closeDocument();
				return null;
			}
		}
		.run();
	}

	public final static class NodeRef extends Value {

        /*
		public NodeRef() {
			data = new byte[12];
		}
        */

        public NodeRef(int docId) {
            data = new byte[4];
            ByteConversion.intToByte(docId, data, 0);
            len = 4;
            pos = 0;
        }        
        
        public NodeRef(int docId, long gid) {
			data = new byte[12];
			ByteConversion.intToByte(docId, data, 0);
			ByteConversion.longToByte(gid, data, 4);
			len = 12;
			pos = 0;
		}

		int getDocId() {
			return ByteConversion.byteToInt(data, 0);
		}

        /*
		long getGid() {
			return ByteConversion.byteToLong(data, 4);
		}
        */

        /*
		void set(int docId, long gid) {
			ByteConversion.intToByte(docId, data, 0);
			ByteConversion.longToByte(gid, data, 4);
			len = 12;
			pos = 0;
		}
        */
	}
    
    private final static class RemovedNode {
        StoredNode node;
        String content;
        NodePath path;
        
        RemovedNode(StoredNode node, NodePath path, String content) {
            this.node = node;
            this.path = path;
            this.content = content;
        }
    }    
    
    /** Delegate for Node Processings : indexing */
    private class NodeProcessor {
        
        private Txn transaction;
        private StoredNode node;
        private NodePath currentPath;
        private IndexSpec idxSpec;
        private FulltextIndexSpec ftIdx;
        private int depth;
        private int level;        
        /** overall switch to activate fulltext indexation */
        private boolean fullTextIndex = true;
        private boolean repairMode = false;
        
        NodeProcessor() {
        }

        public void reset(Txn transaction, StoredNode node, NodePath currentPath) {            
            if (node.getGID() < 0)
                LOG.debug("illegal node: " + node.getGID() + "; " + node.getNodeName());
                //TODO : why continue processing ? return ? -pb
            this.transaction = transaction;
            this.node = node;
            this.currentPath = currentPath;
            idxSpec = ((DocumentImpl)node.getOwnerDocument()).getCollection().getIdxConf(NativeBroker.this);
            ftIdx = idxSpec != null ? idxSpec.getFulltextIndexSpec() : null;
            depth = idxSpec == null ? defaultIndexDepth : idxSpec.getIndexDepth();
            level = ((DocumentImpl)node.getOwnerDocument()).getTreeLevel(node.getGID());
        }
        
        public void reset(Txn transaction, StoredNode node, NodePath currentPath, boolean fullTextIndex) {
            reset(transaction, node, currentPath);
            this.fullTextIndex = fullTextIndex;
        }

        public void setRepairMode(boolean repair) {
            this.repairMode = repair;
        }
        
        /** Updates the various indices */
        public void doIndex() {
            int indexType = RangeIndexSpec.NO_INDEX;
            final boolean isTemp = XmldbURI.TEMP_COLLECTION_URI.equals(((DocumentImpl)node.getOwnerDocument()).getCollection().getURI());
            switch (node.getNodeType()) {
                case Node.ELEMENT_NODE :
                    if (idxSpec != null) {
                        
                        // --move to-- NativeValueIndex
                        RangeIndexSpec spec = idxSpec.getIndexByPath(currentPath);
                        if(spec != null) {
                            indexType = spec.getIndexType();
                        }
                        
//                        // --move to-- NativeValueIndexByQName
//                        RangeIndexSpec qnIdx = idxSpec.getIndexByQName(node.getQName());
//                        if (qnIdx != null && qnameValueIndexation) {
//                            indexType |= RangeIndexSpec.QNAME_INDEX;
//                        }
                    }
                    
                    // --move to-- NativeTextEngine
                    if(ftIdx == null || currentPath == null || ftIdx.match(currentPath))
                        indexType |= RangeIndexSpec.TEXT;
                    if(node.getChildCount() - node.getAttributesCount() > 1) {
                        indexType |= RangeIndexSpec.MIXED_CONTENT;
                    }
                    
                    // --move to-- NativeValueIndex NativeTextEngine
                    ((ElementImpl) node).setIndexType(indexType);
                    
                    // qnameValueIndex.startElement((ElementImpl)node, currentPath, index);
                    notifyStartElement((ElementImpl)node, currentPath, fullTextIndex);
                    break;
                    
                case Node.ATTRIBUTE_NODE :
                    boolean indexAttribs = false;
                    
                    QName qname = node.getQName();
                    if (currentPath != null)
                        currentPath.addComponent(qname);
                    
                    // --move to-- NativeElementIndex NativeValueIndex NativeTextEngine
                    if(fullTextIndex && (ftIdx == null || currentPath == null || ftIdx.matchAttribute(currentPath))) {
                        indexType |= RangeIndexSpec.TEXT;
                        indexAttribs = true;
                    }
                    
                    // --move to-- NativeValueIndex
                    // TODO : valueIndex.storeAttribute( (AttrImpl)node, currentPath, index);
                    GeneralRangeIndexSpec valSpec = null;
                    if (idxSpec != null) {
                        valSpec = idxSpec.getIndexByPath(currentPath);
                        if(valSpec != null) {
                            indexType |= valSpec.getIndexType();
                        }
                    }
                    if (valSpec != null) {
                        valueIndex.setDocument((DocumentImpl)node.getOwnerDocument());
                        valueIndex.storeAttribute(valSpec, (AttrImpl) node);
                    }
                    
                    // qnameValueIndex.storeAttribute( (AttrImpl)node, currentPath, index);
                    notifyStoreAttribute( (AttrImpl)node, currentPath, fullTextIndex);
                    
                    // --move to-- NativeTextEngine
                    // TODO : textEngine.storeAttribute( (AttrImpl)node, currentPath, index);
                    if (indexAttribs && !isTemp )
                        textEngine.storeAttribute(ftIdx, (AttrImpl) node);
                    
//                  --move to-- NativeElementIndex
                    // TODO : elementIndex.storeAttribute(node, currentPath, index);
                    elementIndex.setDocument((DocumentImpl)node.getOwnerDocument()); 
                    
                    NodeProxy p = new NodeProxy(node);
                    p.setIndexType(indexType);                    
                    qname.setNameType(ElementValue.ATTRIBUTE);
                    elementIndex.addNode(qname, p);
                    
                    // --move to-- NativeElementIndex
                    // TODO : elementIndex.storeAttribute(node, currentPath, index);
                    // if the attribute has type ID, store the ID-value
                    // to the element index as well
                    if (((AttrImpl) node).getType() == AttrImpl.ID) {
                        qname = new QName(((AttrImpl) node).getValue(), "", null);
                        //LOG.debug("found ID: " + qname.getLocalName());
                        qname.setNameType(ElementValue.ATTRIBUTE_ID);
                        elementIndex.addNode(qname, p);
                    }
                    
//                    // --move to-- ???
                    if (currentPath != null)
                        currentPath.removeLastComponent();
                    break;
                    
                case Node.TEXT_NODE:
                    // --move to-- NativeTextEngine
                    // TODO textEngine.storeText( (TextImpl) node, currentPath, index);
                    // check if this textual content should be fulltext-indexed
                    // by calling IndexPaths.match(path)
                	if (fullTextIndex && !isTemp) {                		
	                    boolean indexText;
	                    boolean preventTokenization;
	                    if (ftIdx == null || currentPath == null) {
	                    	indexText = true;
	                    	preventTokenization = false;
	                    } else {
	                        indexText = ftIdx.match(currentPath);
	                        preventTokenization = ftIdx.preserveContent(currentPath);
	                    }
	                    if (indexText)
	                        textEngine.storeText(ftIdx, (TextImpl) node, preventTokenization);	                    
                	}
                    
                    notifyStoreText( (TextImpl)node, currentPath, fullTextIndex );
                    // storeText( TextImpl node, NodePath currentPath, boolean fullTextIndexSwitch );
                    break;
            }
        }

        /** Stores this node into the database, if it's an element */
        public void store() {
            if (!repairMode && node.getNodeType() == Node.ELEMENT_NODE && level <= depth) {
                new DOMTransaction(this, domDb, Lock.WRITE_LOCK) {
                    public Object start() throws ReadOnlyException {
                        try {
                            domDb.addValue(transaction, new NodeRef(((DocumentImpl)node.getOwnerDocument()).getDocId(), 
                            		node.getGID()), node.getInternalAddress());
                        } catch (BTreeException e) {
                            LOG.warn(EXCEPTION_DURING_REINDEX, e);
                        } catch (IOException e) {
                            LOG.warn(EXCEPTION_DURING_REINDEX, e);
                        }
                        return null;
                    }
                }
                .run();
            }
        }
        
        /** check available memory */
        private void checkAvailableMemory() {
            if (nodesCount > DEFAULT_NODES_BEFORE_MEMORY_CHECK) {
                final int percent = (int) (run.freeMemory() /
                        (run.totalMemory() / 100));
                if (percent < memMinFree) {
                    //LOG.info(
                    //  "total memory: " + run.totalMemory() + "; free: " + run.freeMemory());
                    flush();
                    System.gc();
                    LOG.info(
                        "total memory: " + run.totalMemory() + "; free: " + run.freeMemory());
                }
            }
        }
        
        /** Updates the various indices and stores this node into the database */
        public void index() {
            ++nodesCount;
            checkAvailableMemory();
            doIndex();
            store();
        }
        
        /** Updates the various indices and stores this node into the database
         * if necessary */
        public void reindex() {
            if (level >= ((DocumentImpl)node.getOwnerDocument()).getMetadata().reindexRequired()) {
                NodeIndexListener listener = ((DocumentImpl)node.getOwnerDocument()).getMetadata().getIndexListener();
                if(listener != null)
                    listener.nodeChanged(node);
                store();
                doIndex();
            }
        }
    }    

    private final class DocumentCallback implements BTreeCallback {
        
        private Collection collection;
        
        public DocumentCallback(Collection collection) {
            this.collection = collection;
        }
        
        public boolean indexInfo(Value key, long pointer) throws TerminatedException {
            try {
                byte type = key.data()[key.start() + 2];
                VariableByteInput istream = collectionsDb.getAsStream(pointer);
                DocumentImpl doc = null;
                if (type == DocumentImpl.BINARY_FILE)
                    doc = new BinaryDocument(NativeBroker.this, collection);
                else
                    doc = new DocumentImpl(NativeBroker.this, collection);
                doc.read(istream);
                //Commented since DocumentImpl forked from what is now StoredNode
                //doc.setInternalAddress(pointer);
                collection.addDocument(null, NativeBroker.this, doc);
            } catch (EOFException e) {
                LOG.debug("EOFException while reading document data", e);
            } catch (IOException e) {
                LOG.debug("IOException while reading document data", e);
            }
            return true;
        }
    }
}
