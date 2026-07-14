/*
const panier = [
    { nom: "Clavier", prix: 45, quantite: 1 },
    { nom: "Souris", prix: 25, quantite: 2 },
    { nom: "Écran", prix: 180, quantite: 1 },
    { nom: "Câble USB", prix: 10, quantite: 3 }
  ];

panier.forEach(element => {
      console.log(`${element.nom} : ${element.quantite} x ${element.prix} = ${element.quantite * element.prix} €`) ;

});

function calculTotal(panier){
    let total = 0 ;
    panier.forEach(element => {
        total += element.quantite * element.prix ;
    });

    return total ; 
}

let total = calculTotal(panier); 
console.log(`total avant remise : ${total} €`) ;

function afficheProduit(panier){
    panier.forEach(element => {
        if(element.quantite > 1) {
            console.log(element);
        }
    });
}

afficheProduit(panier) ;

function remise(panier){
    let total = 0 ;
    let montant = 0 ;
    const remise = 10 ;
    let prixFinal = 0 ;
    panier.forEach(element => {
        total+= element.quantite * element.prix ;
    });
    if(total > 200 ) {
        montant  = total * remise /100 ;
        prixFinal = total - montant ;
        console.log(`remise : ${montant.toFixed(2)} €`) ;
    }
    return prixFinal.toFixed(2) ;
}
console.log(remise(panier)) ; 

*/


async function chargerUtilisateur(){
    try {

    
        const response =await  fetch("https://jsonplaceholder.typicode.com/users") ;
         if(!response.ok){
              throw new Error(`HTTP ${response.status}`) ;
        }
            const data = await response.json();
  
    
  
             data.forEach(element => {
                console.log(`${element.username} - ${element.email}`) ;
            });
}
catch(error){
    console.log("Une erreur s est produite" + error.message) ;
}

}

chargerUtilisateur() ;