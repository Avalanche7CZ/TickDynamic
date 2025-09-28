package com.wildex999.tickdynamic.listinject;

import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;

public class EntityListIterator implements ListIterator<EntityObject> {

    private ListManager list;
    private int currentAge; //Used to verify if iterator is still valid(Concurrent modification)

    private EntityGroup currentGroup;
    private EntityObject currentObject;
    private Iterator<EntityGroup> groupIterator;
    private List<EntityObject> entityList;
    private int currentIndex;
    private int globalIndex;

    public EntityListIterator(ListManager list) {
        this.list = list;
        this.currentAge = list.getAge();
        this.groupIterator = list.getGroupIterator();
        this.currentIndex = 0;
        this.globalIndex = 0;
    }

    @Override
    public void add(EntityObject entityObject) {
        throw new UnsupportedOperationException("add not supported");
    }

    @Override
    public boolean hasNext() {
        if(currentAge != list.age)
            throw new ConcurrentModificationException("List modified before going to next entry");

        while(entityList == null || currentIndex >= entityList.size()) {
            currentIndex = 0;
            entityList = null;

            if(!groupIterator.hasNext())
                return false;

            currentGroup = groupIterator.next();
            if(currentGroup == null)
                return false;

            entityList = currentGroup.entities;
        }

        return true;
    }

    @Override
    public boolean hasPrevious() { return false; }

    @Override
    public EntityObject next() {
        if(currentAge != list.age)
            throw new ConcurrentModificationException("List modified before reading next entry");
        if(!hasNext())
            throw new NoSuchElementException();
        currentObject = entityList.get(currentIndex++);
        globalIndex++;
        return currentObject;
    }

    @Override
    public int nextIndex() { return globalIndex; }

    @Override
    public EntityObject previous() { throw new UnsupportedOperationException("previous not supported"); }

    @Override
    public int previousIndex() { return globalIndex - 1; }

    @Override
    public void remove() { throw new UnsupportedOperationException("remove not supported"); }

    @Override
    public void set(EntityObject e) { throw new UnsupportedOperationException("set not supported"); }

}
