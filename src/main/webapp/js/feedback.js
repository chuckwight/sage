var star1 = new Image();
var star2 = new Image();
star1.src = '/images/star1.gif';
star2.src = '/images/star2.gif';
var set = false;

function showStars(n) {
  if (!set) {
    document.getElementById('vote').innerHTML = (n == 0 ? 'Please rate your Sage experience: ' : '' + n + (n > 1 ? ' stars' : ' star'));
    for (i = 1; i < 6; i++) document.getElementById(i).src = (i <= n ? star2.src : star1.src);
  }
}

function setStars(n,sig) {
  if (!set) ajaxStars(n,sig);
  set = true;
  document.getElementById('sliderspan').style='display:none';
}

function ajaxStars(nStars,sig) {
  var xmlhttp;
  if (nStars==0) return false;
  xmlhttp=GetXmlHttpObject();
  if (xmlhttp==null) {
    alert ('Sorry, your browser does not support AJAX!');
    return false;
  }
  xmlhttp.onreadystatechange=function() {
    var msg;
    switch (nStars) {
      case '1': msg='1 star - If you are dissatisfied with Sage, '
                + 'please take a moment to <a href=/Feedback >tell us why</a>.';
                break;
      case '2': msg='2 stars - If you are dissatisfied with Sage, '
                + 'please take a moment to <a href=/Feedback >tell us why</a>.';
                break;
      case '3': msg='3 stars - Thank you. <a href=/Feedback >Click here</a> '
                + 'to provide additional feedback.';
                break;
      case '4': msg='4 stars - Thank you';
                break;
      case '5': msg='5 stars - Thank you!';
                break;
      default: msg='You clicked ' + nStars + ' stars.';
    }
    if (xmlhttp.readyState==4) {
      document.getElementById('vote').innerHTML=msg;
    }
  }
  xmlhttp.open('GET','Feedback?UserRequest=AjaxRating&NStars='+nStars,true);
  xmlhttp.send(null);
  return false;
}
function GetXmlHttpObject() {
  if (window.XMLHttpRequest) { // code for IE7+, Firefox, Chrome, Opera, Safari
    return new XMLHttpRequest();
  }
  if (window.ActiveXObject) { // code for IE6, IE5
    return new ActiveXObject('Microsoft.XMLHTTP');
  }
  return null;
}