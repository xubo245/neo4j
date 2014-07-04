/**
 * Copyright (c) 2002-2014 "Neo Technology,"
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
package org.neo4j.kernel.impl.nioneo.xa;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.api.exceptions.TransactionFailureException;
import org.neo4j.kernel.api.properties.DefinedProperty;
import org.neo4j.kernel.api.properties.Property;
import org.neo4j.kernel.impl.nioneo.store.DynamicRecord;
import org.neo4j.kernel.impl.nioneo.store.IndexRule;
import org.neo4j.kernel.impl.nioneo.store.LabelTokenRecord;
import org.neo4j.kernel.impl.nioneo.store.NeoStore;
import org.neo4j.kernel.impl.nioneo.store.NeoStoreRecord;
import org.neo4j.kernel.impl.nioneo.store.NodeRecord;
import org.neo4j.kernel.impl.nioneo.store.NodeStore;
import org.neo4j.kernel.impl.nioneo.store.PrimitiveRecord;
import org.neo4j.kernel.impl.nioneo.store.PropertyKeyTokenRecord;
import org.neo4j.kernel.impl.nioneo.store.PropertyRecord;
import org.neo4j.kernel.impl.nioneo.store.RelationshipGroupRecord;
import org.neo4j.kernel.impl.nioneo.store.RelationshipRecord;
import org.neo4j.kernel.impl.nioneo.store.RelationshipStore;
import org.neo4j.kernel.impl.nioneo.store.RelationshipTypeTokenRecord;
import org.neo4j.kernel.impl.nioneo.store.SchemaRule;
import org.neo4j.kernel.impl.nioneo.store.SchemaStore;
import org.neo4j.kernel.impl.nioneo.xa.RecordAccess.RecordProxy;
import org.neo4j.kernel.impl.nioneo.xa.RecordChanges.RecordChange;
import org.neo4j.kernel.impl.nioneo.xa.command.Command;
import org.neo4j.kernel.impl.nioneo.xa.command.Command.Mode;
import org.neo4j.kernel.impl.util.ArrayMap;

import static org.neo4j.kernel.impl.nioneo.store.labels.NodeLabelsField.parseLabelsField;

/**
 * Transaction containing {@link org.neo4j.kernel.impl.nioneo.xa.command.Command commands} reflecting the operations
 * performed in the transaction.
 *
 * This class currently has a symbiotic relationship with {@link KernelTransaction}, with which it always has a 1-1
 * relationship.
 *
 * The idea here is that KernelTransaction will eventually take on the responsibilities of WriteTransaction, such as
 * keeping track of transaction state, serialization and deserialization to and from logical log, and applying things
 * to store. It would most likely do this by keeping a component derived from the current WriteTransaction
 * implementation as a sub-component, responsible for handling logical log commands.
 *
 * The class XAResourceManager plays in here as well, in that it shares responsibilities with WriteTransaction to
 * write data to the logical log. As we continue to refactor this subsystem, XAResourceManager should ideally not know
 * about the logical log, but defer entirely to the Kernel to handle this. Doing that will give the kernel full
 * discretion to start experimenting with higher-performing logical log implementations, without being hindered by
 * having to contend with the JTA compliance layers. In short, it would encapsulate the logical log/storage logic better
 * and thus make it easier to change.
 */
public class TransactionRecordState
{
    private RecordChanges<Long, NeoStoreRecord, Void> neoStoreRecord;
    private final long lastCommittedTxWhenTransactionStarted;
    private final NeoStore neoStore;
    private final IntegrityValidator integrityValidator;
    private final NeoStoreTransactionContext context;
    private boolean prepared;

    /**
     * @param lastCommittedTxWhenTransactionStarted is the highest committed transaction id when this transaction
     *                                              begun. No operations in this transaction are allowed to have
     *                                              taken place before that transaction id. This is used by
     *                                              constraint validation - if a constraint was not online when this
     *                                              transaction begun, it will be verified during prepare. If you are
     *                                              writing code against this API and are unsure about what to set
     *                                              this value to, 0 is a safe choice. That will ensure all
     *                                              constraints are checked.
     */
    public TransactionRecordState( long lastCommittedTxWhenTransactionStarted,
                         NeoStore neoStore, IntegrityValidator integrityValidator,
                         NeoStoreTransactionContext context )
    {
        this.lastCommittedTxWhenTransactionStarted = lastCommittedTxWhenTransactionStarted;
        this.neoStore = neoStore;
        this.integrityValidator = integrityValidator;
        this.context = context;
    }

    public boolean isReadOnly()
    {
        return context.getNodeRecords().changeSize() == 0 && context.getRelRecords().changeSize() == 0 &&
                context.getSchemaRuleChanges().changeSize() == 0 &&
                context.getPropertyRecords().changeSize() == 0 &&
                context.getRelGroupRecords().changeSize() == 0 &&
                context.getPropertyKeyTokenRecords().changeSize() == 0 &&
                context.getLabelTokenRecords().changeSize() == 0 &&
                context.getRelationshipTypeTokenRecords().changeSize() == 0;
    }

    public void extractCommands( List<Command> target ) throws TransactionFailureException
    {
    	assert !prepared : "Transaction has already been prepared";

        int noOfCommands = context.getNodeRecords().changeSize() +
                           context.getRelRecords().changeSize() +
                           context.getPropertyRecords().changeSize() +
                           context.getSchemaRuleChanges().changeSize() +
                           context.getPropertyKeyTokenRecords().changeSize() +
                           context.getLabelTokenRecords().changeSize() +
                           context.getRelationshipTypeTokenRecords().changeSize() +
                           context.getRelGroupRecords().changeSize() +
                           (neoStoreRecord != null ? neoStoreRecord.changeSize() : 0);
        List<Command> commands = new ArrayList<>( noOfCommands );
        for ( RecordProxy<Integer, LabelTokenRecord, Void> record : context.getLabelTokenRecords().changes() )
        {
            Command.LabelTokenCommand command = new Command.LabelTokenCommand();
            command.init(  record.forReadingLinkage()  );
            commands.add( command );
        }
        for ( RecordProxy<Integer, RelationshipTypeTokenRecord, Void> record : context.getRelationshipTypeTokenRecords().changes() )
        {
            Command.RelationshipTypeTokenCommand command = new Command.RelationshipTypeTokenCommand();
            command.init( record.forReadingLinkage() );
            commands.add( command );
        }
        for ( RecordProxy<Integer, PropertyKeyTokenRecord, Void> record : context.getPropertyKeyTokenRecords().changes() )
        {
            Command.PropertyKeyTokenCommand command =
                    new Command.PropertyKeyTokenCommand();
            command.init( record.forReadingLinkage() );
            commands.add( command );
        }

        // Collect nodes, relationships, properties
        List<Command> nodeCommands = new ArrayList<>();
        for ( RecordChange<Long, NodeRecord, Void> change : context.getNodeRecords().changes() )
        {
            NodeRecord record = change.forReadingLinkage();
            integrityValidator.validateNodeRecord( record );
            Command.NodeCommand command = new Command.NodeCommand();
            command.init( change.getBefore(), record );
            nodeCommands.add( command );
        }
        Collections.sort( nodeCommands, COMMAND_SORTER );

        List<Command> relCommands = new ArrayList<>();
        for ( RecordProxy<Long, RelationshipRecord, Void> record : context.getRelRecords().changes() )
        {
            Command.RelationshipCommand command = new Command.RelationshipCommand();
            command.init(  record.forReadingLinkage()  );
            relCommands.add( command );
        }
        Collections.sort( relCommands, COMMAND_SORTER );

        List<Command> propCommands = new ArrayList<>();
        for ( RecordChange<Long, PropertyRecord, PrimitiveRecord> change : context.getPropertyRecords().changes() )
        {
            Command.PropertyCommand command = new Command.PropertyCommand();
            command.init( change.getBefore(), change.forReadingLinkage() );
            propCommands.add( command );
        }
        Collections.sort( propCommands, COMMAND_SORTER );

        List<Command> relGroupCommands = new ArrayList<>();
        for ( RecordProxy<Long, RelationshipGroupRecord, Integer> change : context.getRelGroupRecords().changes() )
        {
            Command.RelationshipGroupCommand command = new Command.RelationshipGroupCommand();
            command.init( change.forReadingData() );
            relGroupCommands.add( command );
        }
        Collections.sort( relGroupCommands, COMMAND_SORTER );
        addFiltered( commands, Mode.CREATE, propCommands, relCommands, nodeCommands, relGroupCommands );
        addFiltered( commands, Mode.UPDATE, propCommands, relCommands, nodeCommands, relGroupCommands );
        addFiltered( commands, Mode.DELETE, propCommands, relCommands, nodeCommands, relGroupCommands );

        if ( neoStoreRecord != null )
        {
            for ( RecordProxy<Long, NeoStoreRecord, Void> change : neoStoreRecord.changes() )
            {
                Command.NeoStoreCommand command = new Command.NeoStoreCommand();
                command.init( change.forReadingData() );
                commands.add( command );
            }
        }
        for ( RecordChange<Long, Collection<DynamicRecord>, SchemaRule> change : context.getSchemaRuleChanges().changes() )
        {
            integrityValidator.validateSchemaRule( change.getAdditionalData() );
            Command.SchemaRuleCommand command = new Command.SchemaRuleCommand();
            command.init( change.getBefore(), change.forChangingData(), change.getAdditionalData() );
            commands.add( command );
        }
        assert commands.size() == noOfCommands : "Expected " + noOfCommands + " final commands, got "
                + commands.size() + " instead";

        integrityValidator.validateTransactionStartKnowledge( lastCommittedTxWhenTransactionStarted );
        prepared = true;
        target.addAll( commands );
    }

    public void relationshipCreate( long id, int typeId, long startNodeId, long endNodeId )
    {
        context.relationshipCreate( id, typeId, startNodeId, endNodeId );
    }

    public ArrayMap<Integer, DefinedProperty> relDelete( long relId )
    {
        return context.relationshipDelete( relId );
    }

    private void addFiltered( Collection<Command> target, Mode mode,
            Collection<? extends Command>... commands )
    {
        for ( Collection<? extends Command> c : commands )
        {
            for ( Command command : c )
            {
                if ( command.getMode() == mode )
                {
                    target.add( command );
                }
            }
        }
    }

    private NodeStore getNodeStore()
    {
        return neoStore.getNodeStore();
    }

    private SchemaStore getSchemaStore()
    {
        return neoStore.getSchemaStore();
    }

    private RelationshipStore getRelationshipStore()
    {
        return neoStore.getRelationshipStore();
    }

    /**
     * Deletes a node by its id, returning its properties which are now removed.
     *
     * @param nodeId The id of the node to delete.
     * @return The properties of the node that were removed during the delete.
     */
    public ArrayMap<Integer, DefinedProperty> nodeDelete( long nodeId )
    {
        NodeRecord nodeRecord = context.getNodeRecords().getOrLoad( nodeId, null ).forChangingData();
        if ( !nodeRecord.inUse() )
        {
            throw new IllegalStateException( "Unable to delete Node[" + nodeId +
                                             "] since it has already been deleted." );
        }
        nodeRecord.setInUse( false );
        nodeRecord.setLabelField( 0, Collections.<DynamicRecord>emptyList() );
        return getAndDeletePropertyChain( nodeRecord );
    }

    private ArrayMap<Integer, DefinedProperty> getAndDeletePropertyChain( NodeRecord nodeRecord )
    {
        return context.getAndDeletePropertyChain( nodeRecord );
    }

    /**
     * Removes the given property identified by its index from the relationship
     * with the given id.
     *
     * @param relId The id of the relationship that is to have the property
     *            removed.
     * @param propertyKey The index key of the property.
     */
    public void relRemoveProperty( long relId, int propertyKey )
    {
        RecordProxy<Long, RelationshipRecord, Void> rel = context.getRelRecords().getOrLoad( relId, null );
        RelationshipRecord relRecord = rel.forReadingLinkage();
        if ( !relRecord.inUse() )
        {
            throw new IllegalStateException( "Property remove on relationship[" +
                                             relId + "] illegal since it has been deleted." );
        }
        context.removeProperty( rel, propertyKey );
    }

    /**
     * Removes the given property identified by indexKeyId of the node with the
     * given id.
     *
     * @param nodeId The id of the node that is to have the property removed.
     * @param propertyKey The index key of the property.
     */
    public void nodeRemoveProperty( long nodeId, int propertyKey )
    {
        RecordProxy<Long, NodeRecord, Void> node = context.getNodeRecords().getOrLoad( nodeId, null );
        NodeRecord nodeRecord = node.forReadingLinkage();
        if ( !nodeRecord.inUse() )
        {
            throw new IllegalStateException( "Property remove on node[" +
                    nodeId + "] illegal since it has been deleted." );
        }
        context.removeProperty( node, propertyKey );
    }

    /**
     * Changes an existing property's value of the given relationship, with the
     * given index to the passed value
     *
     * @param relId The id of the relationship which holds the property to
     *            change.
     * @param propertyKey The index of the key of the property to change.
     * @param value The new value of the property.
     * @return The changed property, as a PropertyData object.
     */
    public DefinedProperty relChangeProperty( long relId, int propertyKey, Object value )
    {
        RecordProxy<Long, RelationshipRecord, Void> rel = context.getRelRecords().getOrLoad( relId, null );
        if ( !rel.forReadingLinkage().inUse() )
        {
            throw new IllegalStateException( "Property change on relationship[" +
                                             relId + "] illegal since it has been deleted." );
        }
        context.primitiveChangeProperty( rel, propertyKey, value );
        return Property.property( propertyKey, value );
    }

    /**
     * Changes an existing property of the given node, with the given index to
     * the passed value
     *
     * @param nodeId The id of the node which holds the property to change.
     * @param propertyKey The index of the key of the property to change.
     * @param value The new value of the property.
     * @return The changed property, as a PropertyData object.
     */
    public DefinedProperty nodeChangeProperty( long nodeId, int propertyKey, Object value )
    {
        RecordProxy<Long, NodeRecord, Void> node = context.getNodeRecords().getOrLoad( nodeId, null ); //getNodeRecord( nodeId );
        if ( !node.forReadingLinkage().inUse() )
        {
            throw new IllegalStateException( "Property change on node[" +
                                             nodeId + "] illegal since it has been deleted." );
        }
        context.primitiveChangeProperty( node, propertyKey, value );
        return Property.property( propertyKey, value );
    }

    /**
     * Adds a property to the given relationship, with the given index and
     * value.
     *
     * @param relId The id of the relationship to which to add the property.
     * @param propertyKey The index of the key of the property to add.
     * @param value The value of the property.
     * @return The added property, as a PropertyData object.
     */
    public DefinedProperty relAddProperty( long relId, int propertyKey, Object value )
    {
        RecordProxy<Long, RelationshipRecord, Void> rel = context.getRelRecords().getOrLoad( relId, null );
        RelationshipRecord relRecord = rel.forReadingLinkage();
        if ( !relRecord.inUse() )
        {
            throw new IllegalStateException( "Property add on relationship[" +
                                             relId + "] illegal since it has been deleted." );
        }
        context.primitiveAddProperty( rel, propertyKey, value );
        return Property.property( propertyKey, value );
    }

    /**
     * Adds a property to the given node, with the given index and value.
     *
     * @param nodeId The id of the node to which to add the property.
     * @param propertyKey The index of the key of the property to add.
     * @param value The value of the property.
     * @return The added property, as a PropertyData object.
     */
    public DefinedProperty nodeAddProperty( long nodeId, int propertyKey, Object value )
    {
        RecordProxy<Long, NodeRecord, Void> node = context.getNodeRecords().getOrLoad( nodeId, null );
        NodeRecord nodeRecord = node.forReadingLinkage();
        if ( !nodeRecord.inUse() )
        {
            throw new IllegalStateException( "Property add on node[" +
                                             nodeId + "] illegal since it has been deleted." );
        }
        context.primitiveAddProperty( node, propertyKey, value );
        return Property.property( propertyKey, value );
    }

    /**
     * Creates a node for the given id
     *
     * @param nodeId The id of the node to create.
     */
    public void nodeCreate( long nodeId )
    {
        NodeRecord nodeRecord = context.getNodeRecords().create( nodeId, null ).forChangingData();
        nodeRecord.setInUse( true );
        nodeRecord.setCreated();
    }

    /**
     * Creates a property index entry out of the given id and string.
     *
     * @param key The key of the property index, as a string.
     * @param id The property index record id.
     */
    public void createPropertyKeyToken( String key, int id )
    {
        context.createPropertyKeyToken( key, id );
    }

    /**
     * Creates a property index entry out of the given id and string.
     *
     * @param name The key of the property index, as a string.
     * @param id The property index record id.
     */
    public void createLabelToken( String name, int id )
    {
        context.createLabelToken( name, id );
    }

    /**
     * Creates a new RelationshipType record with the given id that has the
     * given name.
     *
     * @param id The id of the new relationship type record.
     * @param name The name of the relationship type.
     */
    public void createRelationshipTypeToken( int id, String name )
    {
        context.createRelationshipTypeToken( name, id );
    }

    private static class CommandSorter implements Comparator<Command>
    {
        @Override
        public int compare( Command o1, Command o2 )
        {
            long id1 = o1.getKey();
            long id2 = o2.getKey();
            long diff = id1 - id2;
            if ( diff > Integer.MAX_VALUE )
            {
                return Integer.MAX_VALUE;
            }
            else if ( diff < Integer.MIN_VALUE )
            {
                return Integer.MIN_VALUE;
            }
            else
            {
                return (int) diff;
            }
        }

        @Override
        public boolean equals( Object o )
        {
            return o instanceof CommandSorter;
        }

        @Override
        public int hashCode()
        {
            return 3217;
        }
    }

    private static final CommandSorter COMMAND_SORTER = new CommandSorter();

    private RecordProxy<Long, NeoStoreRecord, Void> getOrLoadNeoStoreRecord()
    {
        if ( neoStoreRecord == null )
        {
            neoStoreRecord = new RecordChanges<>( new RecordChanges.Loader<Long, NeoStoreRecord, Void>()
            {
                @Override
                public NeoStoreRecord newUnused( Long key, Void additionalData )
                {
                    throw new UnsupportedOperationException();
                }

                @Override
                public NeoStoreRecord load( Long key, Void additionalData )
                {
                    return neoStore.asRecord();
                }

                @Override
                public void ensureHeavy( NeoStoreRecord record )
                {
                }

                @Override
                public NeoStoreRecord clone(NeoStoreRecord neoStoreRecord) {
                    // We do not expect to manage the before state, so this operation will not be called.
                    throw new UnsupportedOperationException("Clone on NeoStoreRecord");
                }
            }, false );
        }
        return neoStoreRecord.getOrLoad( 0L, null );
    }

    /**
     * Adds a property to the graph, with the given index and value.
     *
     * @param propertyKey The index of the key of the property to add.
     * @param value The value of the property.
     * @return The added property, as a PropertyData object.
     */
    public DefinedProperty graphAddProperty( int propertyKey, Object value )
    {
        context.primitiveAddProperty( getOrLoadNeoStoreRecord(), propertyKey, value );
        return Property.property( propertyKey, value );
    }

    /**
     * Changes an existing property of the graph, with the given index to
     * the passed value
     *
     * @param propertyKey The index of the key of the property to change.
     * @param value The new value of the property.
     * @return The changed property, as a PropertyData object.
     */
    public DefinedProperty graphChangeProperty( int propertyKey, Object value )
    {
        context.primitiveChangeProperty( getOrLoadNeoStoreRecord(), propertyKey, value );
        return Property.property( propertyKey, value );
    }

    /**
     * Removes the given property identified by indexKeyId of the graph with the
     * given id.
     *
     * @param propertyKey The index key of the property.
     */
    public void graphRemoveProperty( int propertyKey )
    {
        RecordProxy<Long, NeoStoreRecord, Void> recordChange = getOrLoadNeoStoreRecord();
        context.removeProperty( recordChange, propertyKey );
    }

    public void createSchemaRule( SchemaRule schemaRule )
    {
        for(DynamicRecord change : context.getSchemaRuleChanges().create( schemaRule.getId(), schemaRule ).forChangingData())
        {
            change.setInUse( true );
            change.setCreated();
        }
    }

    public void dropSchemaRule( SchemaRule rule )
    {
        RecordProxy<Long, Collection<DynamicRecord>, SchemaRule> change =
                context.getSchemaRuleChanges().getOrLoad( rule.getId(), rule );
        Collection<DynamicRecord> records = change.forChangingData();
        for ( DynamicRecord record : records )
        {
            record.setInUse( false );
        }
    }

    public void addLabelToNode( int labelId, long nodeId )
    {
        NodeRecord nodeRecord = context.getNodeRecords().getOrLoad( nodeId, null ).forChangingData();
        parseLabelsField( nodeRecord ).add( labelId, getNodeStore(), getNodeStore().getDynamicLabelStore() );
    }

    public void removeLabelFromNode( int labelId, long nodeId )
    {
        NodeRecord nodeRecord = context.getNodeRecords().getOrLoad( nodeId, null ).forChangingData();
        parseLabelsField( nodeRecord ).remove( labelId, getNodeStore() );
    }

    public void setConstraintIndexOwner( IndexRule indexRule, long constraintId )
    {
        RecordProxy<Long, Collection<DynamicRecord>, SchemaRule> change =
                context.getSchemaRuleChanges().getOrLoad( indexRule.getId(), indexRule );
        Collection<DynamicRecord> records = change.forChangingData();

        indexRule = indexRule.withOwningConstraint( constraintId );

        records.clear();
        records.addAll( getSchemaStore().allocateFrom( indexRule ) );
    }

    public interface PropertyReceiver
    {
        void receive( DefinedProperty property, long propertyRecordId );
    }

    public long nextNodeId()
    {
        return getNodeStore().nextId();
    }

    public long nextRelationshipId()
    {
        return getRelationshipStore().nextId();
    }
}
