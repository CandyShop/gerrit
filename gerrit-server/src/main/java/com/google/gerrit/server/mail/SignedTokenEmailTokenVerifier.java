// Copyright 2012 Google Inc. All Rights Reserved.

package com.google.gerrit.server.mail;

import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.server.config.AuthConfig;
import com.google.gwtjsonrpc.server.SignedToken;
import com.google.gwtjsonrpc.server.ValidToken;
import com.google.gwtjsonrpc.server.XsrfException;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;

import org.eclipse.jgit.util.Base64;

import java.io.UnsupportedEncodingException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Verifies the token sent by {@link RegisterNewEmailSender}. */
public class SignedTokenEmailTokenVerifier implements EmailTokenVerifier {
  private final SignedToken emailRegistrationToken;

  public static class Module extends AbstractModule {
    @Override
    protected void configure() {
      bind(EmailTokenVerifier.class).to(SignedTokenEmailTokenVerifier.class);
    }
  }

  @Inject
  SignedTokenEmailTokenVerifier(AuthConfig config) {
    emailRegistrationToken = config.getEmailRegistrationToken();
  }

  public String encode(Account.Id accountId, String emailAddress) {
    try {
      String payload = String.format("%s:%s", accountId, emailAddress);
      byte[] utf8 = payload.getBytes("UTF-8");
      String base64 = Base64.encodeBytes(utf8);
      return emailRegistrationToken.newToken(base64);
    } catch (XsrfException e) {
      throw new IllegalArgumentException(e);
    } catch (UnsupportedEncodingException e) {
      throw new IllegalArgumentException(e);
    }
  }

  public ParsedToken decode(String tokenString) throws InvalidTokenException {
    ValidToken token;
    try {
      token = emailRegistrationToken.checkToken(tokenString, null);
    } catch (XsrfException err) {
      throw new InvalidTokenException(err);
    }
    if (token == null || token.getData() == null || token.getData().isEmpty()) {
      throw new InvalidTokenException();
    }

    String payload;
    try {
      payload = new String(Base64.decode(token.getData()), "UTF-8");
    } catch (UnsupportedEncodingException err) {
      throw new InvalidTokenException(err);
    }

    Matcher matcher = Pattern.compile("^([0-9]+):(.+@.+)$").matcher(payload);
    if (!matcher.matches()) {
      throw new InvalidTokenException();
    }

    Account.Id id;
    try {
      id = Account.Id.parse(matcher.group(1));
    } catch (IllegalArgumentException err) {
      throw new InvalidTokenException(err);
    }

    String newEmail = matcher.group(2);
    return new ParsedToken(id, newEmail);
  }
}