/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.api.state;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

import org.neo4j.collection.primitive.Primitive;
import org.neo4j.collection.primitive.PrimitiveIntObjectMap;
import org.neo4j.collection.primitive.PrimitiveIntObjectVisitor;
import org.neo4j.collection.primitive.PrimitiveIntSet;
import org.neo4j.collection.primitive.PrimitiveLongIterator;
import org.neo4j.collection.primitive.PrimitiveLongObjectMap;
import org.neo4j.collection.primitive.PrimitiveLongSet;
import org.neo4j.cursor.Cursor;
import org.neo4j.helpers.collection.Iterables;
import org.neo4j.kernel.api.exceptions.schema.ConstraintValidationException;
import org.neo4j.kernel.api.exceptions.schema.CreateConstraintFailureException;
import org.neo4j.kernel.api.schema.LabelSchemaDescriptor;
import org.neo4j.kernel.api.schema.SchemaDescriptor;
import org.neo4j.kernel.api.schema.SchemaDescriptorPredicates;
import org.neo4j.kernel.api.schema.constaints.ConstraintDescriptor;
import org.neo4j.kernel.api.schema.constaints.IndexBackedConstraintDescriptor;
import org.neo4j.kernel.api.schema.index.IndexDescriptor;
import org.neo4j.kernel.api.txstate.RelationshipChangeVisitorAdapter;
import org.neo4j.kernel.api.txstate.TransactionState;
import org.neo4j.kernel.impl.api.RelationshipVisitor;
import org.neo4j.kernel.impl.api.cursor.TxAllPropertyCursor;
import org.neo4j.kernel.impl.api.cursor.TxIteratorRelationshipCursor;
import org.neo4j.kernel.impl.api.cursor.TxSingleNodeCursor;
import org.neo4j.kernel.impl.api.cursor.TxSinglePropertyCursor;
import org.neo4j.kernel.impl.api.cursor.TxSingleRelationshipCursor;
import org.neo4j.kernel.impl.api.store.RelationshipIterator;
import org.neo4j.kernel.impl.util.InstanceCache;
import org.neo4j.kernel.impl.util.diffsets.DiffSets;
import org.neo4j.kernel.impl.util.diffsets.RelationshipDiffSets;
import org.neo4j.storageengine.api.Direction;
import org.neo4j.storageengine.api.NodeItem;
import org.neo4j.storageengine.api.PropertyItem;
import org.neo4j.storageengine.api.RelationshipItem;
import org.neo4j.storageengine.api.StorageProperty;
import org.neo4j.storageengine.api.txstate.DiffSetsVisitor;
import org.neo4j.storageengine.api.txstate.NodeState;
import org.neo4j.storageengine.api.txstate.PropertyContainerState;
import org.neo4j.storageengine.api.txstate.ReadableDiffSets;
import org.neo4j.storageengine.api.txstate.ReadableRelationshipDiffSets;
import org.neo4j.storageengine.api.txstate.ReadableTransactionState;
import org.neo4j.storageengine.api.txstate.RelationshipState;
import org.neo4j.storageengine.api.txstate.TxStateVisitor;
import org.neo4j.values.storable.TextValue;
import org.neo4j.values.storable.Value;
import org.neo4j.values.storable.ValueTuple;
import org.neo4j.values.storable.Values;

import static org.neo4j.collection.primitive.PrimitiveLongCollections.toPrimitiveIterator;
import static org.neo4j.helpers.collection.Iterables.map;

/**
 * This class contains transaction-local changes to the graph. These changes can then be used to augment reads from the
 * committed state of the database (to make the local changes appear in local transaction read operations). At commit
 * time a visitor is sent into this class to convert the end result of the tx changes into a physical changeset.
 * <p>
 * See {@link org.neo4j.kernel.impl.api.KernelTransactionImplementation} for how this happens.
 * <p>
 * This class is very large, as it has been used as a gathering point to consolidate all transaction state knowledge
 * into one component. Now that that work is done, this class should be refactored to increase transparency in how it
 * works.
 */
public final class TxState implements TransactionState, RelationshipVisitor.Home
{
    private Map<Integer/*Label ID*/, LabelState.Mutable> labelStatesMap;
    private static final LabelState.Defaults LABEL_STATE = new LabelState.Defaults()
    {
        @Override
        Map<Integer, LabelState.Mutable> getMap( TxState state )
        {
            return state.labelStatesMap;
        }

        @Override
        void setMap( TxState state, Map<Integer, LabelState.Mutable> map )
        {
            state.labelStatesMap = map;
        }
    };
    private Map<Long/*Node ID*/, NodeStateImpl> nodeStatesMap;
    private static final NodeStateImpl.Defaults NODE_STATE = new NodeStateImpl.Defaults()
    {
        @Override
        Map<Long, NodeStateImpl> getMap( TxState state )
        {
            return state.nodeStatesMap;
        }

        @Override
        void setMap( TxState state, Map<Long, NodeStateImpl> map )
        {
            state.nodeStatesMap = map;
        }
    };
    private Map<Long/*Relationship ID*/, RelationshipStateImpl> relationshipStatesMap;
    private static final RelationshipStateImpl.Defaults RELATIONSHIP_STATE = new RelationshipStateImpl.Defaults()
    {
        @Override
        Map<Long, RelationshipStateImpl> getMap( TxState state )
        {
            return state.relationshipStatesMap;
        }

        @Override
        void setMap( TxState state, Map<Long, RelationshipStateImpl> map )
        {
            state.relationshipStatesMap = map;
        }
    };

    private PrimitiveIntObjectMap<String> createdLabelTokens;
    private PrimitiveIntObjectMap<String> createdPropertyKeyTokens;
    private PrimitiveIntObjectMap<String> createdRelationshipTypeTokens;

    private GraphState graphState;
    private DiffSets<IndexDescriptor> indexChanges;
    private DiffSets<ConstraintDescriptor> constraintsChanges;

    private PropertyChanges propertyChangesForNodes;

    // Tracks added and removed nodes, not modified nodes
    private DiffSets<Long> nodes;

    // Tracks added and removed relationships, not modified relationships
    private RelationshipDiffSets<Long> relationships;

    private PrimitiveLongObjectMap<PropertyContainerState> propertiesMap = Primitive.longObjectMap();

    /**
     * These two sets are needed because create-delete in same transaction is a no-op in {@link DiffSets}
     * but we still need to provide correct answer in {@link #nodeIsDeletedInThisTx(long)} and
     * {@link #relationshipIsDeletedInThisTx(long)} methods.
     */
    private PrimitiveLongSet nodesDeletedInTx;
    private PrimitiveLongSet relationshipsDeletedInTx;

    private Map<IndexBackedConstraintDescriptor, Long> createdConstraintIndexesByConstraint;

    private Map<LabelSchemaDescriptor, Map<ValueTuple, DiffSets<Long>>> indexUpdates;

    private InstanceCache<TxSingleNodeCursor> singleNodeCursor;
    private InstanceCache<TxIteratorRelationshipCursor> iteratorRelationshipCursor;
    private InstanceCache<TxSingleRelationshipCursor> singleRelationshipCursor;
    private InstanceCache<TxAllPropertyCursor> propertyCursor;
    private InstanceCache<TxSinglePropertyCursor> singlePropertyCursor;

    private boolean hasChanges;
    private boolean hasDataChanges;

    public TxState()
    {
        singleNodeCursor = new InstanceCache<TxSingleNodeCursor>()
        {
            @Override
            protected TxSingleNodeCursor create()
            {
                return new TxSingleNodeCursor( TxState.this, this );
            }
        };
        propertyCursor = new InstanceCache<TxAllPropertyCursor>()
        {
            @Override
            protected TxAllPropertyCursor create()
            {
                return new TxAllPropertyCursor( (Consumer) this );
            }
        };
        singlePropertyCursor = new InstanceCache<TxSinglePropertyCursor>()
        {
            @Override
            protected TxSinglePropertyCursor create()
            {
                return new TxSinglePropertyCursor( (Consumer) this );
            }
        };
        singleRelationshipCursor = new InstanceCache<TxSingleRelationshipCursor>()
        {
            @Override
            protected TxSingleRelationshipCursor create()
            {
                return new TxSingleRelationshipCursor( TxState.this, this );
            }
        };

        iteratorRelationshipCursor = new InstanceCache<TxIteratorRelationshipCursor>()
        {
            @Override
            protected TxIteratorRelationshipCursor create()
            {
                return new TxIteratorRelationshipCursor( TxState.this, this );
            }
        };
    }

    @Override
    public void accept( final TxStateVisitor visitor )
            throws ConstraintValidationException, CreateConstraintFailureException
    {
        // Created nodes
        if ( nodes != null )
        {
            nodes.accept( createdNodesVisitor( visitor ) );
        }

        if ( relationships != null )
        {
            // Created relationships
            relationships.accept( createdRelationshipsVisitor( this, visitor ) );

            // Deleted relationships
            relationships.accept( deletedRelationshipsVisitor( visitor ) );
        }

        // Deleted nodes
        if ( nodes != null )
        {
            nodes.accept( deletedNodesVisitor( visitor ) );
        }

        for ( NodeState node : modifiedNodes() )
        {
            node.accept( nodeVisitor( visitor ) );
        }

        for ( RelationshipState rel : modifiedRelationships() )
        {
            rel.accept( relVisitor( visitor ) );
        }

        if ( graphState != null )
        {
            graphState.accept( graphPropertyVisitor( visitor ) );
        }

        if ( indexChanges != null )
        {
            indexChanges.accept( indexVisitor( visitor ) );
        }

        if ( constraintsChanges != null )
        {
            constraintsChanges.accept( constraintsVisitor( visitor ) );
        }

        if ( createdLabelTokens != null )
        {
            createdLabelTokens.visitEntries( new LabelTokenStateVisitor( visitor ) );
        }

        if ( createdPropertyKeyTokens != null )
        {
            createdPropertyKeyTokens.visitEntries( new PropertyKeyTokenStateVisitor( visitor ) );
        }

        if ( createdRelationshipTypeTokens != null )
        {
            createdRelationshipTypeTokens.visitEntries( new RelationshipTypeTokenStateVisitor( visitor ) );
        }
    }

    private static DiffSetsVisitor<Long> deletedNodesVisitor( final TxStateVisitor visitor )
    {
        return new DiffSetsVisitor.Adapter<Long>()
        {
            @Override
            public void visitRemoved( Long element )
            {
                visitor.visitDeletedNode( element );
            }
        };
    }

    private static DiffSetsVisitor<Long> createdNodesVisitor( final TxStateVisitor visitor )
    {
        return new DiffSetsVisitor.Adapter<Long>()
        {
            @Override
            public void visitAdded( Long element )
            {
                visitor.visitCreatedNode( element );
            }
        };
    }

    private static DiffSetsVisitor<Long> deletedRelationshipsVisitor( final TxStateVisitor visitor )
    {
        return new DiffSetsVisitor.Adapter<Long>()
        {
            @Override
            public void visitRemoved( Long id )
            {
                visitor.visitDeletedRelationship( id );
            }
        };
    }

    private static DiffSetsVisitor<Long> createdRelationshipsVisitor( ReadableTransactionState tx, final TxStateVisitor visitor )
    {
        return new RelationshipChangeVisitorAdapter( tx )
        {
            @Override
            protected void visitAddedRelationship( long relationshipId, int type, long startNode, long endNode )
                    throws ConstraintValidationException
            {
                visitor.visitCreatedRelationship( relationshipId, type, startNode, endNode );
            }
        };
    }

    private static DiffSetsVisitor<ConstraintDescriptor> constraintsVisitor( final TxStateVisitor visitor )
    {
        return new ConstraintDiffSetsVisitor( visitor );
    }

    private static class ConstraintDiffSetsVisitor implements DiffSetsVisitor<ConstraintDescriptor>
    {
        private final TxStateVisitor visitor;

        ConstraintDiffSetsVisitor( TxStateVisitor visitor )
        {
            this.visitor = visitor;
        }

        @Override
        public void visitAdded( ConstraintDescriptor constraint )
                throws ConstraintValidationException, CreateConstraintFailureException
        {
            visitor.visitAddedConstraint( constraint );
        }

        @Override
        public void visitRemoved( ConstraintDescriptor constraint ) throws ConstraintValidationException
        {
            visitor.visitRemovedConstraint( constraint );
        }
    }

    private static DiffSetsVisitor<IndexDescriptor> indexVisitor( final TxStateVisitor visitor )
    {
        return new DiffSetsVisitor<IndexDescriptor>()
        {
            @Override
            public void visitAdded( IndexDescriptor index )
                    throws ConstraintValidationException, CreateConstraintFailureException
            {
                visitor.visitAddedIndex( index );
            }

            @Override
            public void visitRemoved( IndexDescriptor index ) throws ConstraintValidationException
            {
                visitor.visitRemovedIndex( index );
            }
        };
    }

    private static NodeState.Visitor nodeVisitor( final TxStateVisitor visitor )
    {
        return new NodeState.Visitor()
        {
            @Override
            public void visitLabelChanges( long nodeId, Set<Integer> added, Set<Integer> removed )
                    throws ConstraintValidationException
            {
                visitor.visitNodeLabelChanges( nodeId, added, removed );
            }

            @Override
            public void visitPropertyChanges( long entityId, Iterator<StorageProperty> added,
                    Iterator<StorageProperty> changed, Iterator<Integer> removed )
                    throws ConstraintValidationException
            {
                visitor.visitNodePropertyChanges( entityId, added, changed, removed );
            }
        };
    }

    private static PropertyContainerState.Visitor relVisitor( final TxStateVisitor visitor )
    {
        return visitor::visitRelPropertyChanges;
    }

    private static PropertyContainerState.Visitor graphPropertyVisitor( final TxStateVisitor visitor )
    {
        return ( entityId, added, changed, removed ) -> visitor.visitGraphPropertyChanges( added, changed, removed );
    }

    @Override
    public boolean hasChanges()
    {
        return hasChanges;
    }

    @Override
    public Iterable<NodeState> modifiedNodes()
    {
        return NODE_STATE.values( this );
    }

    private DiffSets<Long> getOrCreateLabelStateNodeDiffSets( int labelId )
    {
        return LABEL_STATE.getOrCreate( this, labelId ).getOrCreateNodeDiffSets();
    }

    @Override
    public ReadableDiffSets<Integer> nodeStateLabelDiffSets( long nodeId )
    {
        return NODE_STATE.get( this, nodeId ).labelDiffSets();
    }

    private DiffSets<Integer> getOrCreateNodeStateLabelDiffSets( long nodeId )
    {
        return getOrCreateNodeState( nodeId ).getOrCreateLabelDiffSets();
    }

    @Override
    public Iterator<StorageProperty> augmentGraphProperties( Iterator<StorageProperty> original )
    {
        if ( graphState != null )
        {
            return graphState.augmentProperties( original );
        }
        return original;
    }

    @Override
    public boolean nodeIsAddedInThisTx( long nodeId )
    {
        return nodes != null && nodes.isAdded( nodeId );
    }

    @Override
    public boolean relationshipIsAddedInThisTx( long relationshipId )
    {
        return relationships != null && relationships.isAdded( relationshipId );
    }

    private void changed()
    {
        hasChanges = true;
    }

    private void dataChanged()
    {
        changed();
        hasDataChanges = true;
    }

    @Override
    public void nodeDoCreate( long id )
    {
        nodes().add( id );
        dataChanged();
    }

    @Override
    public void nodeDoDelete( long nodeId )
    {
        if ( nodes().remove( nodeId ) )
        {
            recordNodeDeleted( nodeId );
        }

        if ( nodeStatesMap != null )
        {
            NodeStateImpl nodeState = nodeStatesMap.remove( nodeId );
            if ( nodeState != null )
            {
                ReadableDiffSets<Integer> diff = nodeState.labelDiffSets();
                for ( Integer label : diff.getAdded() )
                {
                    getOrCreateLabelStateNodeDiffSets( label ).remove( nodeId );
                }
                nodeState.clearIndexDiffs( nodeId );
                nodeState.clear();
            }
        }
        dataChanged();
    }

    @Override
    public void relationshipDoCreate( long id, int relationshipTypeId, long startNodeId, long endNodeId )
    {
        relationships().add( id );

        if ( startNodeId == endNodeId )
        {
            getOrCreateNodeState( startNodeId ).addRelationship( id, relationshipTypeId, Direction.BOTH );
        }
        else
        {
            getOrCreateNodeState( startNodeId ).addRelationship( id, relationshipTypeId, Direction.OUTGOING );
            getOrCreateNodeState( endNodeId ).addRelationship( id, relationshipTypeId, Direction.INCOMING );
        }

        getOrCreateRelationshipState( id ).setMetaData( startNodeId, endNodeId, relationshipTypeId );

        dataChanged();
    }

    @Override
    public boolean nodeIsDeletedInThisTx( long nodeId )
    {
        return nodesDeletedInTx != null && nodesDeletedInTx.contains( nodeId );
    }

    @Override
    public boolean nodeModifiedInThisTx( long nodeId )
    {
        return nodeIsAddedInThisTx( nodeId ) || nodeIsDeletedInThisTx( nodeId ) || hasNodeState( nodeId );
    }

    @Override
    public void relationshipDoDelete( long id, int type, long startNodeId, long endNodeId )
    {
        if ( relationships().remove( id ) )
        {
            recordRelationshipDeleted( id );
        }

        if ( startNodeId == endNodeId )
        {
            getOrCreateNodeState( startNodeId ).removeRelationship( id, type, Direction.BOTH );
        }
        else
        {
            getOrCreateNodeState( startNodeId ).removeRelationship( id, type, Direction.OUTGOING );
            getOrCreateNodeState( endNodeId ).removeRelationship( id, type, Direction.INCOMING );
        }

        if ( relationshipStatesMap != null )
        {
            RelationshipStateImpl removed = relationshipStatesMap.remove( id );
            if ( removed != null )
            {
                removed.clear();
            }
        }

        dataChanged();
    }

    @Override
    public void relationshipDoDeleteAddedInThisTx( long relationshipId )
    {
        RELATIONSHIP_STATE.get( this, relationshipId ).accept( this::relationshipDoDelete );
    }

    @Override
    public boolean relationshipIsDeletedInThisTx( long relationshipId )
    {
        return relationshipsDeletedInTx != null && relationshipsDeletedInTx.contains( relationshipId );
    }

    @Override
    public void nodeDoAddProperty( long nodeId, int newPropertyKeyId, Value value )
    {
        NodeStateImpl nodeState = getOrCreateNodeState( nodeId );
        nodeState.addProperty( newPropertyKeyId, value );
        nodePropertyChanges().addProperty( nodeId, newPropertyKeyId, value );
        dataChanged();
    }

    @Override
    public void nodeDoChangeProperty( long nodeId, int propertyKeyId, Value replacedValue, Value newValue )
    {
        getOrCreateNodeState( nodeId ).changeProperty( propertyKeyId, newValue );
        nodePropertyChanges().changeProperty( nodeId, propertyKeyId, replacedValue, newValue );
        dataChanged();
    }

    @Override
    public void relationshipDoReplaceProperty( long relationshipId, int propertyKeyId, Value replacedValue,
            Value newValue )
    {
        if ( replacedValue != Values.NO_VALUE )
        {
            getOrCreateRelationshipState( relationshipId ).changeProperty( propertyKeyId, newValue );
        }
        else
        {
            getOrCreateRelationshipState( relationshipId ).addProperty( propertyKeyId, newValue );
        }
        dataChanged();
    }

    @Override
    public void graphDoReplaceProperty( int propertyKeyId, Value replacedValue, Value newValue )
    {
        if ( replacedValue != Values.NO_VALUE )
        {
            getOrCreateGraphState().changeProperty( propertyKeyId, newValue );
        }
        else
        {
            getOrCreateGraphState().addProperty( propertyKeyId, newValue );
        }
        dataChanged();
    }

    @Override
    public void nodeDoRemoveProperty( long nodeId, int propertyKeyId, Value removedValue )
    {
        getOrCreateNodeState( nodeId ).removeProperty( propertyKeyId, removedValue );
        nodePropertyChanges().removeProperty( nodeId, propertyKeyId, removedValue );
        dataChanged();
    }

    @Override
    public void relationshipDoRemoveProperty( long relationshipId, int propertyKeyId, Value removedValue )
    {
        getOrCreateRelationshipState( relationshipId ).removeProperty( propertyKeyId, removedValue );
        dataChanged();
    }

    @Override
    public void graphDoRemoveProperty( int propertyKeyId, Value removedValue )
    {
        getOrCreateGraphState().removeProperty( propertyKeyId, removedValue );
        dataChanged();
    }

    @Override
    public void nodeDoAddLabel( int labelId, long nodeId )
    {
        getOrCreateLabelStateNodeDiffSets( labelId ).add( nodeId );
        getOrCreateNodeStateLabelDiffSets( nodeId ).add( labelId );
        dataChanged();
    }

    @Override
    public void nodeDoRemoveLabel( int labelId, long nodeId )
    {
        getOrCreateLabelStateNodeDiffSets( labelId ).remove( nodeId );
        getOrCreateNodeStateLabelDiffSets( nodeId ).remove( labelId );
        dataChanged();
    }

    @Override
    public void registerProperties( long ref, PropertyContainerState state )
    {
        propertiesMap.put( ref, state );
    }

    @Override
    public void labelDoCreateForName( String labelName, int id )
    {
        if ( createdLabelTokens == null )
        {
            createdLabelTokens = Primitive.intObjectMap();
        }
        createdLabelTokens.put( id, labelName );
        changed();
    }

    @Override
    public void propertyKeyDoCreateForName( String propertyKeyName, int id )
    {
        if ( createdPropertyKeyTokens == null )
        {
            createdPropertyKeyTokens = Primitive.intObjectMap();
        }
        createdPropertyKeyTokens.put( id, propertyKeyName );
        changed();
    }

    @Override
    public void relationshipTypeDoCreateForName( String labelName, int id )
    {
        if ( createdRelationshipTypeTokens == null )
        {
            createdRelationshipTypeTokens = Primitive.intObjectMap();
        }
        createdRelationshipTypeTokens.put( id, labelName );
        changed();
    }

    @Override
    public NodeState getNodeState( long id )
    {
        return NODE_STATE.get( this, id );
    }

    @Override
    public RelationshipState getRelationshipState( long id )
    {
        return RELATIONSHIP_STATE.get( this, id );
    }

    @Override
    public PropertyContainerState getPropertiesState( long reference )
    {
        return propertiesMap.get( reference );
    }

    @Override
    public Cursor<NodeItem> augmentSingleNodeCursor( Cursor<NodeItem> cursor, long nodeId )
    {
        return hasChanges ? singleNodeCursor.get().init( cursor, nodeId ) : cursor;
    }

    @Override
    public Cursor<PropertyItem> augmentPropertyCursor( Cursor<PropertyItem> cursor,
            PropertyContainerState propertyContainerState )
    {
        return propertyContainerState.hasPropertyChanges() ?
                propertyCursor.get().init( cursor, propertyContainerState ) : cursor;
    }

    @Override
    public Cursor<PropertyItem> augmentSinglePropertyCursor( Cursor<PropertyItem> cursor,
            PropertyContainerState propertyContainerState, int propertyKeyId )
    {
        return propertyContainerState.hasPropertyChanges() ?
                singlePropertyCursor.get().init( cursor, propertyContainerState, propertyKeyId ) : cursor;
    }

    @Override
    public PrimitiveIntSet augmentLabels( PrimitiveIntSet labels, NodeState nodeState )
    {
        ReadableDiffSets<Integer> labelDiffSets = nodeState.labelDiffSets();
        if ( !labelDiffSets.isEmpty() )
        {
            labelDiffSets.getRemoved().forEach( labels::remove );
            labelDiffSets.getAdded().forEach( labels::add );
        }
        return labels;
    }

    @Override
    public Cursor<RelationshipItem> augmentSingleRelationshipCursor( Cursor<RelationshipItem> cursor,
            long relationshipId )
    {
        return hasChanges ? singleRelationshipCursor.get().init( cursor, relationshipId ) : cursor;
    }

    @Override
    public Cursor<RelationshipItem> augmentNodeRelationshipCursor( Cursor<RelationshipItem> cursor,
            NodeState nodeState,
            Direction direction )
    {
        return nodeState.hasPropertyChanges() || nodeState.hasRelationshipChanges()
               ? iteratorRelationshipCursor.get().init( cursor, nodeState.getAddedRelationships( direction ) )
               : cursor;
    }
    @Override
    public Cursor<RelationshipItem> augmentNodeRelationshipCursor( Cursor<RelationshipItem> cursor,
            NodeState nodeState,
            Direction direction,
            int[] relTypes )
    {
        return nodeState.hasPropertyChanges() || nodeState.hasRelationshipChanges()
               ? iteratorRelationshipCursor.get().init( cursor, nodeState.getAddedRelationships( direction, relTypes ) )
               : cursor;
    }

    @Override
    public Cursor<RelationshipItem> augmentRelationshipsGetAllCursor( Cursor<RelationshipItem> cursor )
    {
        return hasChanges && relationships != null && !relationships.isEmpty()
               ? iteratorRelationshipCursor.get().init( cursor, toPrimitiveIterator( relationships.getAdded().iterator() ) )
               : cursor;
    }

    @Override
    public ReadableDiffSets<Long> nodesWithLabelChanged( int labelId )
    {
        return LABEL_STATE.get( this, labelId ).nodeDiffSets();
    }

    @Override
    public void indexRuleDoAdd( IndexDescriptor descriptor )
    {
        DiffSets<IndexDescriptor> diff = indexChangesDiffSets();
        if ( !diff.unRemove( descriptor ) )
        {
            diff.add( descriptor );
        }
        changed();
    }

    @Override
    public void indexDoDrop( IndexDescriptor descriptor )
    {
        indexChangesDiffSets().remove( descriptor );
        changed();
    }

    @Override
    public boolean indexDoUnRemove( IndexDescriptor descriptor )
    {
        return indexChangesDiffSets().unRemove( descriptor );
    }

    @Override
    public ReadableDiffSets<IndexDescriptor> indexDiffSetsByLabel( int labelId )
    {
        return indexChangesDiffSets().filterAdded( SchemaDescriptorPredicates.hasLabel( labelId ) );
    }

    @Override
    public ReadableDiffSets<IndexDescriptor> indexChanges()
    {
        return ReadableDiffSets.Empty.ifNull( indexChanges );
    }

    private DiffSets<IndexDescriptor> indexChangesDiffSets()
    {
        if ( indexChanges == null )
        {
            indexChanges = new DiffSets<>();
        }
        return indexChanges;
    }

    @Override
    public ReadableDiffSets<Long> addedAndRemovedNodes()
    {
        return ReadableDiffSets.Empty.ifNull( nodes );
    }

    private DiffSets<Long> nodes()
    {
        if ( nodes == null )
        {
            nodes = new DiffSets<>();
        }
        return nodes;
    }

    @Override
    public int augmentNodeDegree( long nodeId, int degree, Direction direction )
    {
        return NODE_STATE.get( this, nodeId ).augmentDegree( direction, degree );
    }

    @Override
    public int augmentNodeDegree( long nodeId, int degree, Direction direction, int typeId )
    {
        return NODE_STATE.get( this, nodeId ).augmentDegree( direction, degree, typeId );
    }

    @Override
    public PrimitiveIntSet nodeRelationshipTypes( long nodeId )
    {
        return NODE_STATE.get( this, nodeId ).relationshipTypes();
    }

    @Override
    public ReadableRelationshipDiffSets<Long> addedAndRemovedRelationships()
    {
        return ReadableRelationshipDiffSets.Empty.ifNull( relationships );
    }

    private RelationshipDiffSets<Long> relationships()
    {
        if ( relationships == null )
        {
            relationships = new RelationshipDiffSets<>( this );
        }
        return relationships;
    }

    @Override
    public Iterable<RelationshipState> modifiedRelationships()
    {
        return RELATIONSHIP_STATE.values( this );
    }

    private NodeStateImpl getOrCreateNodeState( long nodeId )
    {
        return NODE_STATE.getOrCreate( this, nodeId );
    }

    private RelationshipStateImpl getOrCreateRelationshipState( long relationshipId )
    {
        return RELATIONSHIP_STATE.getOrCreate( this, relationshipId );
    }

    private GraphState getOrCreateGraphState()
    {
        if ( graphState == null )
        {
            graphState = new GraphState();
        }
        return graphState;
    }

    @Override
    public void constraintDoAdd( IndexBackedConstraintDescriptor constraint, long indexId )
    {
        constraintsChangesDiffSets().add( constraint );
        createdConstraintIndexesByConstraint().put( constraint, indexId );
        changed();
    }

    @Override
    public void constraintDoAdd( ConstraintDescriptor constraint )
    {
        constraintsChangesDiffSets().add( constraint );
        changed();
    }

    @Override
    public ReadableDiffSets<ConstraintDescriptor> constraintsChangesForLabel( int labelId )
    {
        return constraintsChangesDiffSets().filterAdded( SchemaDescriptorPredicates.hasLabel( labelId ) );
    }

    @Override
    public ReadableDiffSets<ConstraintDescriptor> constraintsChangesForSchema( SchemaDescriptor descriptor )
    {
        return constraintsChangesDiffSets().filterAdded( SchemaDescriptor.equalTo( descriptor ) );
    }

    @Override
    public ReadableDiffSets<ConstraintDescriptor> constraintsChangesForRelationshipType( int relTypeId )
    {
        return constraintsChangesDiffSets().filterAdded( SchemaDescriptorPredicates.hasRelType( relTypeId ) );
    }

    @Override
    public ReadableDiffSets<ConstraintDescriptor> constraintsChanges()
    {
        return ReadableDiffSets.Empty.ifNull( constraintsChanges );
    }

    private DiffSets<ConstraintDescriptor> constraintsChangesDiffSets()
    {
        if ( constraintsChanges == null )
        {
            constraintsChanges = new DiffSets<>();
        }
        return constraintsChanges;
    }

    @Override
    public void constraintDoDrop( ConstraintDescriptor constraint )
    {
        constraintsChangesDiffSets().remove( constraint );
        if ( constraint.enforcesUniqueness() )
        {
            indexDoDrop( getIndexForIndexBackedConstraint( (IndexBackedConstraintDescriptor) constraint ) );
        }
        changed();
    }

    @Override
    public boolean constraintDoUnRemove( ConstraintDescriptor constraint )
    {
        return constraintsChangesDiffSets().unRemove( constraint );
    }

    @Override
    public Iterable<IndexDescriptor> constraintIndexesCreatedInTx()
    {
        if ( createdConstraintIndexesByConstraint != null && !createdConstraintIndexesByConstraint.isEmpty() )
        {
            return map( this::getIndexForIndexBackedConstraint, createdConstraintIndexesByConstraint.keySet() );
        }
        return Iterables.empty();
    }

    @Override
    public Long indexCreatedForConstraint( ConstraintDescriptor constraint )
    {
        return createdConstraintIndexesByConstraint == null ? null :
                createdConstraintIndexesByConstraint.get( constraint );
    }

    @Override
    public ReadableDiffSets<Long> indexUpdatesForScan( IndexDescriptor descriptor )
    {
        return ReadableDiffSets.Empty.ifNull( getIndexUpdatesForScan( descriptor.schema() ) );
    }

    @Override
    public ReadableDiffSets<Long> indexUpdatesForSeek( IndexDescriptor descriptor, ValueTuple values )
    {
        assert values != null;
        return ReadableDiffSets.Empty.ifNull( getIndexUpdatesForSeek( descriptor.schema(), values, /*create=*/false ) );
    }

    @Override
    public ReadableDiffSets<Long> indexUpdatesForRangeSeekByNumber( IndexDescriptor descriptor,
                                                                    Number lower, boolean includeLower,
                                                                    Number upper, boolean includeUpper )
    {
        return ReadableDiffSets.Empty.ifNull(
            getIndexUpdatesForRangeSeekByNumber( descriptor, lower, includeLower, upper, includeUpper )
        );
    }

    private ReadableDiffSets<Long> getIndexUpdatesForRangeSeekByNumber( IndexDescriptor descriptor,
                                                                        Number lower, boolean includeLower,
                                                                        Number upper, boolean includeUpper )
    {
        TreeMap<ValueTuple, DiffSets<Long>> sortedUpdates = getSortedIndexUpdates( descriptor.schema() );
        if ( sortedUpdates == null )
        {
            return null;
        }

        ValueTuple selectedLower;
        boolean selectedIncludeLower;

        ValueTuple selectedUpper;
        boolean selectedIncludeUpper;

        //TODO: Get working with composite indexes'
        if ( lower == null )
        {
            selectedLower = ValueTuple.of( Values.MIN_NUMBER );
            selectedIncludeLower = true;
        }
        else
        {
            selectedLower = ValueTuple.of( Values.numberValue( lower ) );
            selectedIncludeLower = includeLower;
        }

        if ( upper == null )
        {
            selectedUpper = ValueTuple.of( Values.MAX_NUMBER );
            selectedIncludeUpper = true;
        }
        else
        {
            selectedUpper = ValueTuple.of( Values.numberValue( upper ) );
            selectedIncludeUpper = includeUpper;
        }

        DiffSets<Long> diffs = new DiffSets<>();

        Collection<DiffSets<Long>> inRange =
                sortedUpdates.subMap( selectedLower, selectedIncludeLower,
                                      selectedUpper, selectedIncludeUpper ).values();
        for ( DiffSets<Long> diffForSpecificValue : inRange )
        {
            diffs.addAll( diffForSpecificValue.getAdded().iterator() );
            diffs.removeAll( diffForSpecificValue.getRemoved().iterator() );
        }
        return diffs;
    }

    @Override
    public ReadableDiffSets<Long> indexUpdatesForRangeSeekByString( IndexDescriptor descriptor,
                                                                    String lower, boolean includeLower,
                                                                    String upper, boolean includeUpper )
    {
        return ReadableDiffSets.Empty.ifNull(
                getIndexUpdatesForRangeSeekByString( descriptor, lower, includeLower, upper, includeUpper )
        );
    }

    private ReadableDiffSets<Long> getIndexUpdatesForRangeSeekByString( IndexDescriptor descriptor,
                                                                        String lower, boolean includeLower,
                                                                        String upper, boolean includeUpper )
    {
        TreeMap<ValueTuple, DiffSets<Long>> sortedUpdates = getSortedIndexUpdates( descriptor.schema() );
        if ( sortedUpdates == null )
        {
            return null;
        }

        ValueTuple selectedLower;
        boolean selectedIncludeLower;

        ValueTuple selectedUpper;
        boolean selectedIncludeUpper;

        //TODO: Get working with composite indexes
        if ( lower == null )
        {
            selectedLower = ValueTuple.of( Values.MIN_STRING );
            selectedIncludeLower = true;
        }
        else
        {
            selectedLower = ValueTuple.of( Values.stringValue( lower ) );
            selectedIncludeLower = includeLower;
        }

        if ( upper == null )
        {
            selectedUpper = ValueTuple.of( Values.MAX_STRING );
            selectedIncludeUpper = false;
        }
        else
        {
            selectedUpper = ValueTuple.of( Values.stringValue( upper ) );
            selectedIncludeUpper = includeUpper;
        }

        DiffSets<Long> diffs = new DiffSets<>();
        Collection<DiffSets<Long>> inRange =
                sortedUpdates.subMap(   selectedLower, selectedIncludeLower,
                                        selectedUpper, selectedIncludeUpper ).values();
        for ( DiffSets<Long> diffForSpecificValue : inRange )
        {
            diffs.addAll( diffForSpecificValue.getAdded().iterator() );
            diffs.removeAll( diffForSpecificValue.getRemoved().iterator() );
        }
        return diffs;
    }

    @Override
    public ReadableDiffSets<Long> indexUpdatesForRangeSeekByPrefix( IndexDescriptor descriptor, String prefix )
    {
        return ReadableDiffSets.Empty.ifNull( getIndexUpdatesForRangeSeekByPrefix( descriptor, prefix ) );
    }

    private ReadableDiffSets<Long> getIndexUpdatesForRangeSeekByPrefix( IndexDescriptor descriptor, String prefix )
    {
        TreeMap<ValueTuple, DiffSets<Long>> sortedUpdates = getSortedIndexUpdates( descriptor.schema() );
        if ( sortedUpdates == null )
        {
            return null;
        }
        //TODO: get working with composite indexes
        ValueTuple floor = ValueTuple.of( Values.stringValue( prefix ) );
        DiffSets<Long> diffs = new DiffSets<>();
        for ( Map.Entry<ValueTuple,DiffSets<Long>> entry : sortedUpdates.tailMap( floor ).entrySet() )
        {
            ValueTuple key = entry.getKey();
            if ( ((TextValue)key.getOnlyValue()).stringValue().startsWith( prefix ) )
            {
                DiffSets<Long> diffSets = entry.getValue();
                diffs.addAll( diffSets.getAdded().iterator() );
                diffs.removeAll( diffSets.getRemoved().iterator() );
            }
            else
            {
                break;
            }
        }
        return diffs;
    }

    // Ensure sorted index updates for a given index. This is needed for range query support and
    // may involve converting the existing hash map first
    //
    private TreeMap<ValueTuple, DiffSets<Long>> getSortedIndexUpdates( LabelSchemaDescriptor descriptor )
    {
        if ( indexUpdates == null )
        {
            return null;
        }
        Map<ValueTuple, DiffSets<Long>> updates = indexUpdates.get( descriptor );
        if ( updates == null )
        {
            return null;
        }
        TreeMap<ValueTuple,DiffSets<Long>> sortedUpdates;
        if ( updates instanceof TreeMap )
        {
            sortedUpdates = (TreeMap<ValueTuple,DiffSets<Long>>) updates;
        }
        else
        {
            sortedUpdates = new TreeMap<>( ValueTuple.COMPARATOR );
            sortedUpdates.putAll( updates );
            indexUpdates.put( descriptor, sortedUpdates );
        }
        return sortedUpdates;
    }

    @Override
    public void indexDoUpdateEntry( LabelSchemaDescriptor descriptor, long nodeId,
            ValueTuple propertiesBefore, ValueTuple propertiesAfter )
    {
        NodeStateImpl nodeState = getOrCreateNodeState( nodeId );
        Map<ValueTuple,DiffSets<Long>> updates = getIndexUpdatesByDescriptor( descriptor, true);
        if ( propertiesBefore != null )
        {
            DiffSets<Long> before = getIndexUpdatesForSeek( updates, propertiesBefore, true );
            //noinspection ConstantConditions
            before.remove( nodeId );
            if ( before.getRemoved().contains( nodeId ) )
            {
                nodeState.addIndexDiff( before );
            }
            else
            {
                nodeState.removeIndexDiff( before );
            }
        }
        if ( propertiesAfter != null )
        {
            DiffSets<Long> after = getIndexUpdatesForSeek( updates, propertiesAfter, true );
            //noinspection ConstantConditions
            after.add( nodeId );
            if ( after.getAdded().contains( nodeId ) )
            {
                nodeState.addIndexDiff( after );
            }
            else
            {
                nodeState.removeIndexDiff( after );
            }
        }
    }

    private DiffSets<Long> getIndexUpdatesForSeek(
            LabelSchemaDescriptor schema, ValueTuple values, boolean create )
    {
        Map<ValueTuple,DiffSets<Long>> updates = getIndexUpdatesByDescriptor( schema, create );
        if ( updates != null )
        {
            return getIndexUpdatesForSeek( updates, values, create );
        }
        return null;
    }

    private DiffSets<Long> getIndexUpdatesForSeek( Map<ValueTuple,DiffSets<Long>> updates,
            ValueTuple values, boolean create )
    {
        DiffSets<Long> diffs = updates.get( values );
        if ( diffs == null && create )
        {
            updates.put( values, diffs = new DiffSets<>() );
        }
        return diffs;
    }

    private Map<ValueTuple,DiffSets<Long>> getIndexUpdatesByDescriptor( LabelSchemaDescriptor schema,
            boolean create )
    {
        if ( indexUpdates == null )
        {
            if ( !create )
            {
                return null;
            }
            indexUpdates = new HashMap<>();
        }
        Map<ValueTuple, DiffSets<Long>> updates = indexUpdates.get( schema );
        if ( updates == null )
        {
            if ( !create )
            {
                return null;
            }
            indexUpdates.put( schema, updates = new HashMap<>() );
        }
        return updates;
    }

    private DiffSets<Long> getIndexUpdatesForScan( LabelSchemaDescriptor schema )
    {
        if ( indexUpdates == null )
        {
            return null;
        }
        Map<ValueTuple, DiffSets<Long>> updates = indexUpdates.get( schema );
        if ( updates == null )
        {
            return null;
        }
        DiffSets<Long> diffs = new DiffSets<>();
        for ( DiffSets<Long> diffSet : updates.values() )
        {
            diffs.addAll( diffSet.getAdded().iterator() );
            diffs.removeAll( diffSet.getRemoved().iterator() );
        }
        return diffs;
    }

    private Map<IndexBackedConstraintDescriptor, Long> createdConstraintIndexesByConstraint()
    {
        if ( createdConstraintIndexesByConstraint == null )
        {
            createdConstraintIndexesByConstraint = new HashMap<>();
        }
        return createdConstraintIndexesByConstraint;
    }

    private IndexDescriptor getIndexForIndexBackedConstraint( IndexBackedConstraintDescriptor constraint )
    {
        return constraint.ownedIndexDescriptor();
    }

    private boolean hasNodeState( long nodeId )
    {
        return nodeStatesMap != null && nodeStatesMap.containsKey( nodeId );
    }

    private PropertyChanges nodePropertyChanges()
    {
        return propertyChangesForNodes == null ?
                propertyChangesForNodes = new PropertyChanges() : propertyChangesForNodes;
    }

    @Override
    public PrimitiveLongIterator augmentNodesGetAll( PrimitiveLongIterator committed )
    {
        return addedAndRemovedNodes().augment( committed );
    }

    @Override
    public RelationshipIterator augmentRelationshipsGetAll( RelationshipIterator committed )
    {
        return addedAndRemovedRelationships().augment( committed );
    }

    @Override
    public <EX extends Exception> boolean relationshipVisit( long relId, RelationshipVisitor<EX> visitor ) throws EX
    {
        return RELATIONSHIP_STATE.get( this, relId ).accept( visitor );
    }

    @Override
    public boolean hasDataChanges()
    {
        return hasDataChanges;
    }

    private void recordNodeDeleted( long id )
    {
        if ( nodesDeletedInTx == null )
        {
            nodesDeletedInTx = Primitive.longSet();
        }
        nodesDeletedInTx.add( id );
    }

    private void recordRelationshipDeleted( long id )
    {
        if ( relationshipsDeletedInTx == null )
        {
            relationshipsDeletedInTx = Primitive.longSet();
        }
        relationshipsDeletedInTx.add( id );
    }

    private static class LabelTokenStateVisitor implements PrimitiveIntObjectVisitor<String,RuntimeException>
    {
        private final TxStateVisitor visitor;

        LabelTokenStateVisitor( TxStateVisitor visitor )
        {
            this.visitor = visitor;
        }

        @Override
        public boolean visited( int key, String value )
        {
            visitor.visitCreatedLabelToken( value, key );
            return false;
        }
    }

    private static class PropertyKeyTokenStateVisitor implements PrimitiveIntObjectVisitor<String,RuntimeException>
    {
        private final TxStateVisitor visitor;

        PropertyKeyTokenStateVisitor( TxStateVisitor visitor )
        {
            this.visitor = visitor;
        }

        @Override
        public boolean visited( int key, String value ) throws RuntimeException
        {
            visitor.visitCreatedPropertyKeyToken( value, key );
            return false;
        }
    }

    private static class RelationshipTypeTokenStateVisitor implements PrimitiveIntObjectVisitor<String,RuntimeException>
    {
        private final TxStateVisitor visitor;

        RelationshipTypeTokenStateVisitor( TxStateVisitor visitor )
        {
            this.visitor = visitor;
        }

        @Override
        public boolean visited( int key, String value ) throws RuntimeException
        {
            visitor.visitCreatedRelationshipTypeToken( value, key );
            return false;
        }
    }
}
