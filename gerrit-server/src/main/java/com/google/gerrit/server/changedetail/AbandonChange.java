// Copyright (C) 2009 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.


package com.google.gerrit.server.changedetail;

import com.google.gerrit.common.ChangeHooks;
import com.google.gerrit.common.data.ReviewResult;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.ChangeMessage;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.ChangeUtil;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.mail.AbandonedSender;
import com.google.gerrit.server.mail.EmailException;
import com.google.gerrit.server.project.ChangeControl;
import com.google.gerrit.server.project.InvalidChangeOperationException;
import com.google.gerrit.server.project.NoSuchChangeException;
import com.google.gwtorm.server.AtomicUpdate;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;

import java.util.concurrent.Callable;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

public class AbandonChange implements Callable<ReviewResult> {

  private final AbandonedSender.Factory abandonedSenderFactory;
  private final ChangeControl.Factory changeControlFactory;
  private final ReviewDb db;
  private final IdentifiedUser currentUser;
  private final ChangeHooks hooks;

  @Argument(index = 0, required = true, multiValued = false, usage = "change to abandon")
  private Change.Id changeId;

  public void setChangeId(final Change.Id changeId) {
    this.changeId = changeId;
  }

  @Option(name = "--message", aliases = {"-m"},
          usage = "optional message to append to change")
  private String message;

  public void setMessage(final String message) {
    this.message = message;
  }

  @Inject
  AbandonChange(final AbandonedSender.Factory abandonedSenderFactory,
      final ChangeControl.Factory changeControlFactory, final ReviewDb db,
      final IdentifiedUser currentUser, final ChangeHooks hooks) {
    this.abandonedSenderFactory = abandonedSenderFactory;
    this.changeControlFactory = changeControlFactory;
    this.db = db;
    this.currentUser = currentUser;
    this.hooks = hooks;

    changeId = null;
    message = null;
  }

  @Override
  public ReviewResult call() throws EmailException,
      InvalidChangeOperationException, NoSuchChangeException, OrmException {
    if (changeId == null) {
      throw new InvalidChangeOperationException("changeId is required");
    }

    final ReviewResult result = new ReviewResult();
    result.setChangeId(changeId);

    final ChangeControl control = changeControlFactory.validateFor(changeId);
    final Change change = db.changes().get(changeId);
    final PatchSet.Id patchSetId = change.currentPatchSetId();
    final PatchSet patch = db.patchSets().get(patchSetId);
    if (!control.canAbandon()) {
      result.addError(new ReviewResult.Error(
          ReviewResult.Error.Type.ABANDON_NOT_PERMITTED));
    } else if (patch == null) {
      throw new NoSuchChangeException(changeId);
    } else {

      // Create a message to accompany the abandoned change
      final ChangeMessage cmsg = new ChangeMessage(
          new ChangeMessage.Key(changeId, ChangeUtil.messageUUID(db)),
          currentUser.getAccountId(), patchSetId);
      final StringBuilder msgBuf =
          new StringBuilder("Patch Set " + patchSetId.get() + ": Abandoned");
      if (message != null && message.length() > 0) {
        msgBuf.append("\n\n");
        msgBuf.append(message);
      }
      cmsg.setMessage(msgBuf.toString());

      // Abandon the change
      final Change updatedChange = db.changes().atomicUpdate(changeId,
          new AtomicUpdate<Change>() {
        @Override
        public Change update(Change change) {
          if (change.getStatus().isOpen()) {
            change.setStatus(Change.Status.ABANDONED);
            ChangeUtil.updated(change);
            return change;
          } else {
            return null;
          }
        }
      });

      if (updatedChange == null) {
        result.addError(new ReviewResult.Error(
            ReviewResult.Error.Type.CHANGE_IS_CLOSED));
        return result;
      }

      ChangeUtil.updatedChange(db, currentUser, updatedChange, cmsg,
                               abandonedSenderFactory);
      hooks.doChangeAbandonedHook(updatedChange, currentUser.getAccount(),
                                  message, db);
    }

    return result;
  }
}
