  var agreeTerms = document.getElementById('terms');
  var agreeNoRefunds = document.getElementById('norefunds');
  var purchase = document.getElementById('purchase');
  function showPurchase() {
    if (agreeTerms.checked && agreeNoRefunds.checked) purchase.style = 'display:inline';
   	else purchase.style = 'display:none';
  }
  var price = 5;  // base monthly price set here and in Util.price
  var nTokens = 100;
  var amtPaid = "";
  var nTokensInp = document.getElementById("nTokens");
   		
  function updateAmount() {
    nTokens = nTokensInp.options[nTokensInp.selectedIndex].value;
	switch (nTokens) {
	case "100": amtPaid=price; break;
	case "500": amtPaid=4*price; break;
	}
	document.getElementById("amt").innerHTML=nTokens + 'tokens - $' + amtPaid + '.00 USD';
  }
  updateAmount();
  
  function initPayPalButton(hashedId) {
    paypal.Buttons({
    style: {
      shape: 'pill',
      color: 'gold',
      layout: 'vertical',
      label: 'checkout',         
    },
    createOrder: function(data, actions) {
      return actions.order.create({
        purchase_units: [{"description":nTokens + " Sage tokens for user: " + hashedId,"amount":{"currency_code":"USD","value":amtPaid+".00"}}]});
      },
      onApprove: function(data, actions) {
        return actions.order.capture().then(function(orderData) {
          // Full available details
          console.log('Capture result', orderData, JSON.stringify(orderData, null, 2));
          // Submit form
          document.getElementById('orderdetails').value=JSON.stringify(orderData, null, 2);
          document.getElementById('activationForm').submit();
          // actions.redirect('thank_you.html');
        });
      },
      onError: function(err) {
        console.log(err);
      }
    }).render('#paypal-button-container');  
  }
  
  function verifySubscription(hashedId) {
    var xmlhttp = new XMLHttpRequest();
    var url = 'https://sage.chemvantage.org/launch?verify=' + hashedId;
    xmlhttp.open('GET',url,true);
    xmlhttp.onreadystatechange=function() {
      if (xmlhttp.readyState==4) {
        if (this.responseText == 'true') document.getElementById('purchase').innerHTML = '<h2>Your subscription is active.</h2>';
      }
    }
    xmlhttp.send();
  }
