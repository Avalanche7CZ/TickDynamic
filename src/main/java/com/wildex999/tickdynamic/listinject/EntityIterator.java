package com.wildex999.tickdynamic.listinject;

import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

public class EntityIterator implements Iterator<EntityObject> {
    private ListManager list;
    private int currentAge;
    private EntityGroup currentGroup;
    private EntityObject currentObject;
    private Iterator<EntityGroup> groupIterator;
    private List<EntityObject> entityList;
    private int currentIndex;
    private static final int MAX_ITERATION_GUARD = 10000;

    public EntityIterator(ListManager list, int age) {
        this.list = list;
        this.currentAge = age;
        this.groupIterator = list.getGroupIterator();
        this.currentIndex = 0;
    }

    @Override
    public boolean hasNext() {
        if (currentAge != list.age)
            throw new ConcurrentModificationException("List modified before going to next entry");
        int guard = 0;
        while (guard++ < MAX_ITERATION_GUARD) {
            // If entityList is null or exhausted, move to next group
            while (entityList == null || currentIndex >= entityList.size()) {
                if (!groupIterator.hasNext())
                    return false;
                currentGroup = groupIterator.next();
                if (currentGroup == null || currentGroup.entities == null || currentGroup.entities.isEmpty()) {
                    entityList = null;
                    currentIndex = 0;
                    continue;
                }
                entityList = currentGroup.entities;
                currentIndex = 0;
            }
            // Skip tile entities
            while (entityList != null && currentIndex < entityList.size()) {
                EntityObject obj = entityList.get(currentIndex);
                if (obj != null && obj.TD_selfTileEntity == null) {
                    return true;
                }
                currentIndex++;
            }
        }
        System.err.println("[TickDynamic][FATAL] EntityIterator.hasNext() exceeded max iterations! Breaking out to prevent server hang.");
        return false;
    }

    @Override
    public EntityObject next() {
        if (currentAge != list.age)
            throw new ConcurrentModificationException("List modified before going to next entry");
        if (!hasNext())
            throw new NoSuchElementException();
        currentObject = entityList.get(currentIndex++);
        return currentObject;
    }

    @Override
    public void remove() {
        if (currentAge != list.age)
            throw new ConcurrentModificationException("List modified before going to next entry");
        if (currentObject == null)
            return;
        list.remove(currentObject);
        currentAge++;
        currentIndex--;
        if (currentIndex < 0)
            currentIndex = 0;
    }
}
