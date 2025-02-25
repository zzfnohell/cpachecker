/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2014  Dirk Beyer
 *  All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 *  CPAchecker web page:
 *    http://cpachecker.sosy-lab.org
 */
package org.sosy_lab.cpachecker.cpa.usage.storage;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableSet;
import java.util.SortedSet;
import java.util.TreeSet;
import org.sosy_lab.cpachecker.cpa.usage.UsageInfo;
import org.sosy_lab.cpachecker.cpa.usage.UsageState;

public class UnrefinedUsagePointSet implements AbstractUsagePointSet {
  private final NavigableSet<UsagePoint> topUsages;
  private final Map<UsagePoint, UsageInfoSet> usageInfoSets;

  public UnrefinedUsagePointSet() {
    topUsages = new TreeSet<>();
    usageInfoSets = new HashMap<>();
  }

  public void add(UsageInfo newInfo) {
    UsageInfoSet targetSet;
    UsagePoint newPoint = newInfo.createUsagePoint();
    if (usageInfoSets.containsKey(newPoint)) {
      targetSet = usageInfoSets.get(newPoint);
    } else {
      targetSet = new UsageInfoSet();
      usageInfoSets.put(newPoint, targetSet);
    }
    add(newPoint);
    targetSet.add(newInfo);
  }

  private void add(UsagePoint newPoint) {
    if (!topUsages.contains(newPoint)) {
      //Put newPoint in the right place in tree
      Iterator<UsagePoint> iterator = topUsages.iterator();
      while (iterator.hasNext()) {
        UsagePoint point = iterator.next();
        if (newPoint.covers(point)) {
          iterator.remove();
          newPoint.addCoveredUsage(point);
        } else if (point.covers(newPoint)) {
          point.addCoveredUsage(newPoint);
          return;
        }
      }
      topUsages.add(newPoint);
    }
  }

  public UsageInfoSet getUsageInfo(UsagePoint point) {
    return usageInfoSets.get(point);
  }

  @Override
  public int size() {
    int result = 0;

    for (Entry<UsagePoint, UsageInfoSet> entry : usageInfoSets.entrySet()) {
      result += entry.getValue().size();
    }

    return result;
  }

  public void reset() {
    topUsages.clear();
    usageInfoSets.clear();
  }

  public void remove(UsageState pUstate) {
    //Attention! Use carefully. May not work
    for (UsagePoint point : new TreeSet<>(usageInfoSets.keySet())) {
      UsageInfoSet uset = usageInfoSets.get(point);
      boolean b = uset.remove(pUstate);
      if (b) {
        if (uset.isEmpty()) {
          usageInfoSets.remove(point);
        }
        //May be two usages related to the same state. This is abstractState !
        //return;
      }
    }
  }

  public Iterator<UsagePoint> getPointIterator() {
    return new TreeSet<>(topUsages).iterator();
  }

  public Iterator<UsagePoint> getPointIteratorFrom(UsagePoint p) {
    return new TreeSet<>(topUsages.tailSet(p)).iterator();
  }

  public int getNumberOfTopUsagePoints() {
    return topUsages.size();
  }

  public void remove(UsagePoint currentUsagePoint) {
    usageInfoSets.remove(currentUsagePoint);
    topUsages.remove(currentUsagePoint);
    currentUsagePoint.getCoveredUsages().forEach(this::add);
  }

  SortedSet<UsagePoint> getTopUsages() {
    return topUsages;
  }
}
